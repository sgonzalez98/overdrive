package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H2-backed index of every .mp4 recording on disk + its sidecar metadata.
 *
 * <p>Replaces the prior model where every list query walked all dirs and
 * parsed every JSON sidecar — that was O(N) disk + O(N) JSON parse per
 * request, plus a 5-second in-memory cache that didn't survive cross-UID
 * reads. With ~1000 clips the request took ~2 minutes; with 5000+ it
 * was unusable.
 *
 * <p>Architecture: pure-Java H2 file at {@code /data/local/tmp/overdrive_recordings_h2}.
 * Same pattern as {@link com.overdrive.app.trips.TripDatabase}: daemon UID
 * 2000 owns the file, app UID reads via the {@code /api/recordings} HTTP
 * surface (not direct JDBC). Cross-UID reads go through HTTP only —
 * {@code FILE_LOCK=SOCKET} is for cross-process coordination within the
 * daemon JVM, not cross-UID JDBC.
 *
 * <p><b>Backward compat:</b>
 * <ul>
 *   <li>Legacy clips with no sidecar are indexed with all sidecar columns
 *       NULL/0. They are visible in unfiltered list queries, but any
 *       explicit severity / proximity / class filter intentionally
 *       excludes them — SQL evaluates {@code NULL IN (...)} as UNKNOWN, so
 *       the WHERE clauses built by {@code buildWhere()} drop NULL rows.
 *       Clients must clear those filters to see legacy clips; this is by
 *       design so filtered views show only metadata-bearing rows.
 *   <li>First boot after upgrade has empty index; warmup walks every dir
 *       once (background, with progress) and populates it. Costs the same
 *       ~2 min as today, paid exactly once. Subsequent boots see all rows.
 *   <li>A clip on disk with no row is repaired on-demand by the API
 *       handler when it spots the gap during a list query.
 *   <li>A row whose mp4 is gone is lazy-deleted on read.
 * </ul>
 *
 * <p><b>Concurrency:</b>
 * <ul>
 *   <li>One H2 connection, accessed serially via internal monitor.
 *       H2's default isolation already gives us per-statement consistency,
 *       and PreparedStatement / ResultSet are NOT thread-safe.
 *   <li>{@link #upsert} / {@link #remove} / {@link #queryRecordings}
 *       all acquire the same internal monitor on this object.
 *   <li>Warmup runs on a single background thread — parallel walking
 *       isn't worth the bug surface for a one-shot operation.
 * </ul>
 *
 * <p><b>Thread model:</b> Construction + init() on the daemon main
 * startup thread. After that all access is via the daemon HTTP worker
 * pool plus one warmup thread plus FileObserver callback threads.
 */
public final class RecordingsIndex {

    private static final String TAG = "RecordingsIndex";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DB_PATH = "/data/local/tmp/overdrive_recordings_h2";
    // DB_CLOSE_ON_EXIT=FALSE to avoid H2's JVM shutdown hook racing the
    // daemon close path. Same justification as TripDatabase — the orphaned
    // lock file would otherwise block the next CameraDaemon boot with
    // "Locked by another process".
    //
    // FILE_LOCK=SOCKET: process-level lock via a localhost socket, NOT
    // suitable for cross-UID coordination. App UID never opens this DB
    // directly; it reads via /api/recordings.
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";

    // Filename patterns mirror RecordingsApiHandler exactly. Kept in sync
    // there too because the parser is the single point of truth — any
    // change to filename grammar updates both.
    private static final Pattern CAM_PATTERN =
            Pattern.compile("cam(\\d+)?_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern EVENT_PATTERN =
            Pattern.compile("event_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern PROXIMITY_PATTERN =
            Pattern.compile("proximity_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern DVR_PATTERN =
            Pattern.compile("dvr_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");

    // Filename-stamp format. Locale.US for the same reason as everywhere
    // else: the writer formats with Locale.US, and parsing under e.g. Thai
    // locale interprets the year as Buddhist Era and dumps every clip
    // ~543 years off.
    private static final ThreadLocal<SimpleDateFormat> FMT_FILENAME =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US));

    // Schema version. Bump when the column layout changes; createSchema()
    // runs ALTER TABLE ADD COLUMN IF NOT EXISTS for every additive change so
    // existing on-disk DBs migrate forward without a wipe.
    //   v1 → v2: added the `storage` column (INTERNAL / SD_CARD / USB), so the
    //            recordings library can filter by physical volume.
    private static final int SCHEMA_VERSION = 2;

    // Singleton — one index per daemon process.
    private static volatile RecordingsIndex INSTANCE;

    public static synchronized RecordingsIndex getInstance() {
        if (INSTANCE == null) INSTANCE = new RecordingsIndex();
        return INSTANCE;
    }

    private Connection connection;
    private volatile boolean initialized = false;

    // Warmup state — single source of truth for whether the API should
    // return {warming: true} on /api/recordings until the first scan
    // completes. Volatile reads are fine; only one writer (the warmup
    // thread).
    private final AtomicBoolean warmupRunning = new AtomicBoolean(false);
    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final AtomicInteger warmupTotal = new AtomicInteger(0);
    private final AtomicInteger warmupDone = new AtomicInteger(0);

    private RecordingsIndex() {}

    // =================================================================
    // Lifecycle
    // =================================================================

    /**
     * Open + migrate the H2 file. Idempotent. Called from CameraDaemon
     * init. Returns true if the DB is ready for queries; false on
     * unrecoverable error (UI falls back to direct-FS mode).
     */
    public synchronized boolean init() {
        if (initialized) return true;
        logger.info("Initializing RecordingsIndex at " + DB_PATH);

        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("H2 driver not found — check gradle deps", e);
            return false;
        }

        // Same retry-on-stale-lock pattern as TripDatabase. SIGKILL of the
        // previous daemon can leave a stale .lock.db that blocks reopen.
        int maxRetries = 3;
        int retryDelayMs = 1000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                logger.info("H2 recordings connection established");
                try (Statement stmt = connection.createStatement()) {
                    // 8 MiB cache — same as TripDatabase. Tuned for the
                    // ~1000-row typical workload; queries are mostly
                    // index seeks + small fact-table reads, the cache
                    // mainly absorbs index pages.
                    stmt.execute("SET CACHE_SIZE 8192");
                }
                createSchema();
                initialized = true;
                logger.info("RecordingsIndex initialized (schema v" + SCHEMA_VERSION + ")");
                return true;
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean lockErr = msg != null
                        && (msg.contains("Locked by another process")
                                || msg.contains("lock.db")
                                || msg.contains("already in use"));
                if (lockErr && attempt < maxRetries) {
                    logger.warn("Index DB locked (attempt " + attempt + "/" + maxRetries + "), cleaning stale locks");
                    cleanupStaleLocks();
                    try { Thread.sleep((long) retryDelayMs * attempt); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    logger.error("Failed to init RecordingsIndex: " + msg, e);
                    return false;
                }
            }
        }
        return false;
    }

    public synchronized void close() {
        if (connection != null) {
            try { connection.close(); }
            catch (Exception e) { logger.warn("Index close failed: " + e.getMessage()); }
            connection = null;
        }
        initialized = false;
    }

    private void cleanupStaleLocks() {
        try {
            File lock = new File(DB_PATH + ".lock.db");
            if (lock.exists()) {
                long age = System.currentTimeMillis() - lock.lastModified();
                if (age > 5 * 60 * 1000L && lock.delete()) {
                    logger.info("Deleted stale lock file (age " + (age / 1000) + "s)");
                }
            }
            File trace = new File(DB_PATH + ".trace.db");
            if (trace.exists()) trace.delete();
        } catch (Exception e) {
            logger.debug("Lock cleanup failed: " + e.getMessage());
        }
    }

    private void createSchema() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS recordings (" +
                "  filename        VARCHAR(256) PRIMARY KEY," +
                "  abs_path        VARCHAR(512) NOT NULL," +
                "  type            VARCHAR(16) NOT NULL," +
                "  camera_id       INT DEFAULT 0," +
                "  ts_ms           BIGINT NOT NULL," +
                "  size_bytes      BIGINT NOT NULL DEFAULT 0," +
                "  mp4_mtime       BIGINT NOT NULL DEFAULT 0," +
                "  sidecar_mtime   BIGINT NOT NULL DEFAULT 0," +
                "  schema_version  INT DEFAULT 0," +
                // Sidecar denorm — filterable columns
                "  peak_severity   VARCHAR(16)," +
                "  peak_proximity  VARCHAR(16)," +
                "  person_count    INT DEFAULT 0," +
                "  vehicle_count   INT DEFAULT 0," +
                "  bike_count      INT DEFAULT 0," +
                "  animal_count    INT DEFAULT 0," +
                "  hero_thumb      VARCHAR(256)," +
                "  actor_classes   VARCHAR(256)," +   // CSV lowercase
                "  place_short     VARCHAR(128)," +
                "  place_medium    VARCHAR(192)," +
                "  place_display   VARCHAR(256)," +
                "  place_country   VARCHAR(8)," +
                "  place_source    VARCHAR(32)," +
                "  start_lat       DOUBLE," +
                "  start_lng       DOUBLE," +
                // Date-bucket helpers, populated at insert time so the
                // /api/recordings/dates and /api/recordings GROUP-BY
                // queries can hit a covering index instead of recomputing
                // the date string per row.
                "  ymd             VARCHAR(10)," +    // "yyyy-MM-dd" local
                // Physical volume the clip lives on: "INTERNAL" / "SD_CARD" /
                // "USB", classified from abs_path at index time. NULL on rows
                // written before v2 (the storage filter treats NULL via the
                // path-derivation fallback in rowToJson). Appended LAST so the
                // upsert MERGE's positional VALUES list stays append-only.
                "  storage         VARCHAR(16)" +
                ")"
            );

            // v1 → v2 migration for DBs created before the `storage` column
            // existed. ADD COLUMN IF NOT EXISTS is a no-op on a fresh v2 table
            // (the CREATE above already has it) and additive on an old v1 file,
            // so this is safe to run unconditionally on every open. Existing
            // rows get storage=NULL and are backfilled lazily by reconcile()/
            // upsert as files are re-touched; rowToJson also derives the tag
            // from the path when the column is NULL, so the UI never shows a
            // blank badge for a legacy row.
            stmt.execute("ALTER TABLE recordings ADD COLUMN IF NOT EXISTS storage VARCHAR(16)");

            // Indexes — covering most common access patterns.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_ts ON recordings(ts_ms DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_type_ts ON recordings(type, ts_ms DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_ymd ON recordings(ymd)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_place ON recordings(place_short)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_country ON recordings(place_country)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_severity ON recordings(peak_severity)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_storage ON recordings(storage)");

            // Schema version table — for future migrations.
            //
            // Column is `meta_key`, not `key`. H2 2.2.x (build 224) promoted
            // KEY to a reserved word — it's now part of MERGE's `KEY(...)`
            // grammar — and rejects bare `key` as a column identifier with
            // "expected identifier" at parse time. The whole index init
            // bails on this CREATE, the ctor returns false, and the
            // recordings API silently falls back to direct-FS scanning
            // (no thumbnails, no place chips, no warming counters). Use a
            // non-reserved name and a quoted constraint identifier to
            // avoid the same trap with KEY in MERGE.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS recordings_meta (" +
                "  meta_key VARCHAR(64) PRIMARY KEY," +
                "  meta_value VARCHAR(256)" +
                ")"
            );
            stmt.execute("MERGE INTO recordings_meta KEY(meta_key) VALUES('schema_version', '"
                    + SCHEMA_VERSION + "')");
        }
    }

    // =================================================================
    // Mutators
    // =================================================================

    /**
     * Upsert one recording row. Called by:
     * <ul>
     *   <li>Warmup, on first scan after a fresh boot.
     *   <li>FileObserver, after CREATE/MOVED_TO of a finalised .mp4.
     *   <li>SidecarGeoUpdater, after geo merge into the JSON.
     *   <li>The API handler's repair-on-read path.
     * </ul>
     *
     * @return true if the row was inserted/updated, false on parse failure.
     */
    public synchronized boolean upsert(File mp4) {
        if (!initialized) return false;
        if (mp4 == null || !mp4.isFile() || !mp4.canRead()) return false;
        if (!mp4.getName().endsWith(".mp4")) return false;

        Row row = parse(mp4);
        if (row == null) return false;
        return upsertRow(row);
    }

    /**
     * Single-row MERGE using a pre-parsed {@link Row}. Used by the warmup
     * pipeline (parser pool produces Rows, single writer drains them) so
     * the parse cost runs concurrently while writes stay serialised on
     * one JDBC connection.
     */
    synchronized boolean upsertRow(Row row) {
        if (!initialized || row == null) return false;
        String sql =
            "MERGE INTO recordings KEY(filename) VALUES (" +
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, row.filename);
            ps.setString(2, row.absPath);
            ps.setString(3, row.type);
            ps.setInt(4, row.cameraId);
            ps.setLong(5, row.tsMs);
            ps.setLong(6, row.sizeBytes);
            ps.setLong(7, row.mp4Mtime);
            ps.setLong(8, row.sidecarMtime);
            ps.setInt(9, row.schemaVersion);
            setNullableString(ps, 10, row.peakSeverity);
            setNullableString(ps, 11, row.peakProximity);
            ps.setInt(12, row.personCount);
            ps.setInt(13, row.vehicleCount);
            ps.setInt(14, row.bikeCount);
            ps.setInt(15, row.animalCount);
            setNullableString(ps, 16, row.heroThumb);
            setNullableString(ps, 17, row.actorClasses);
            setNullableString(ps, 18, row.placeShort);
            setNullableString(ps, 19, row.placeMedium);
            setNullableString(ps, 20, row.placeDisplay);
            setNullableString(ps, 21, row.placeCountry);
            setNullableString(ps, 22, row.placeSource);
            setNullableDouble(ps, 23, row.startLat);
            setNullableDouble(ps, 24, row.startLng);
            ps.setString(25, row.ymd);
            setNullableString(ps, 26, row.storage);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            logger.warn("upsertRow failed for " + row.filename + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Drop a row by filename. Called on DELETE_FROM_FS / explicit delete.
     * No-op when the filename isn't present.
     */
    public synchronized boolean remove(String filename) {
        if (!initialized || filename == null || filename.isEmpty()) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM recordings WHERE filename = ?")) {
            ps.setString(1, filename);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            logger.warn("remove failed for " + filename + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * True when {@code filename} is present in the index. Used by the
     * API handler's repair-on-read path to avoid double-upserting files
     * that are already known.
     */
    public synchronized boolean contains(String filename) {
        if (!initialized || filename == null) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM recordings WHERE filename = ? LIMIT 1")) {
            ps.setString(1, filename);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // =================================================================
    // Warmup
    // =================================================================

    /**
     * Background warmup. Walks every dir, parses every clip, populates
     * the index. Called from CameraDaemon post-init on a dedicated
     * thread. Idempotent — second call is a no-op while first is still
     * running, and short-circuits when {@code warmupComplete} is set.
     *
     * <p>Even on a fresh database with 1000 clips this takes ~1-2 min.
     * The API handler short-circuits to {@code {warming: true, progress}}
     * during this window so the UI shows a progress skeleton instead
     * of a partial list.
     */
    public void warmupAsync() {
        if (warmupComplete.get()) return;
        if (!warmupRunning.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                // Fast path: a previous daemon already walked every file and
                // wrote `warmup_state=complete` to recordings_meta. If the row
                // count in the index still matches what's on disk (within a
                // small tolerance for in-flight writes), we skip the full
                // re-walk and run reconcile() instead — that's an O(N) stat()
                // pass with no JSON parse, typically <500 ms even on a 2k-clip
                // library. The user-visible /api/recordings call therefore
                // returns indexed rows immediately on second-and-later boots
                // instead of seeing `warming=true` for 60 s.
                if (canFastPathWarmup()) {
                    warmupComplete.set(true);
                    logger.info("Warmup fast-path: persisted complete + count match, running reconcile");
                    try {
                        reconcile();
                    } catch (Throwable thr) {
                        logger.warn("Fast-path reconcile failed: " + thr.getMessage());
                    }
                    return;
                }

                runWarmup();
                // Only mark complete on a clean run. A crash mid-warmup
                // would otherwise leave the index partial while the API
                // reports complete=true, suppressing the progress skeleton
                // and the repair-on-read fallback (which only fires when
                // totalCount==0). Leaving complete=false lets a later
                // warmupAsync() retry.
                warmupComplete.set(true);
                persistWarmupComplete();
            } catch (Throwable thr) {
                logger.error("Warmup crashed: " + thr.getMessage(), thr);
            } finally {
                warmupRunning.set(false);
            }
        }, "RecordingsIndexWarmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Persisted-warmup fast path. Returns true when the previous daemon
     * already walked every file and the index row count is still close to
     * the on-disk file count. "Close" here means ±2: a couple of in-flight
     * writes between the previous shutdown and this boot are tolerated
     * because reconcile() will pick them up. A larger drift (user manually
     * deleted clips through a file manager, SD card swapped) drops back to
     * the full walk so we don't gloss over a real mismatch.
     */
    private boolean canFastPathWarmup() {
        if (!initialized) return false;
        String state = readMeta("warmup_state");
        if (!"complete".equals(state)) return false;
        int indexedCount = countIndexedRows();
        if (indexedCount <= 0) return false;
        int diskCount = countDiskFiles();
        // Allow ±2 drift for in-flight writes during the previous shutdown.
        // Anything bigger forces a full re-walk.
        if (Math.abs(diskCount - indexedCount) > 2) {
            logger.info("Warmup fast-path skipped: disk=" + diskCount
                    + " indexed=" + indexedCount + " (diff > 2)");
            return false;
        }
        return true;
    }

    private int countIndexedRows() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM recordings");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            logger.warn("countIndexedRows failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Cheap on-disk file count across all recordings/surveillance/proximity
     * dirs. Uses {@link StorageManager#listMp4Files} (FUSE shell-fallback
     * compatible). Names are deduped so mirror dirs don't double-count.
     */
    private int countDiskFiles() {
        StorageManager sm = StorageManager.getInstance();
        Set<String> names = new HashSet<>();
        scanDirNames(names, sm.getAllRecordingsDirs());
        scanDirNames(names, sm.getAllSurveillanceDirs());
        scanDirNames(names, sm.getAllProximityDirs());
        return names.size();
    }

    private synchronized void persistWarmupComplete() {
        writeMeta("warmup_state", "complete");
        writeMeta("warmup_indexed_count", String.valueOf(countIndexedRows()));
        writeMeta("warmup_completed_ts", String.valueOf(System.currentTimeMillis()));
    }

    private synchronized String readMeta(String key) {
        if (!initialized) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT meta_value FROM recordings_meta WHERE meta_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void writeMeta(String key, String value) {
        if (!initialized) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO recordings_meta KEY(meta_key) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("writeMeta(" + key + ") failed: " + e.getMessage());
        }
    }

    private void runWarmup() {
        long t0 = System.currentTimeMillis();
        StorageManager sm = StorageManager.getInstance();

        // Invalidate the persisted "complete" flag at the start of a full
        // re-walk. If we crash partway through and a future daemon reads the
        // meta row, the fast-path must NOT short-circuit on a stale flag —
        // the index can be in any state right now.
        writeMeta("warmup_state", "in_progress");

        // Phase 1: enumerate every candidate file across every dir.
        // Done in one pass so we have an honest total for progress.
        List<DirEntry> entries = new ArrayList<>();
        addDirFiles(entries, sm.getAllRecordingsDirs(), null);
        addDirFiles(entries, sm.getAllSurveillanceDirs(), null);
        addDirFiles(entries, sm.getAllProximityDirs(), null);
        // Legacy paths — keep mirrored with RecordingsApiHandler.
        addDirFiles(entries,
                List.of(new File("/storage/emulated/0/Android/data/com.overdrive.app/files")),
                null);
        addDirFiles(entries,
                List.of(new File("/storage/emulated/0/Android/data/com.overdrive.app/files/recordings")),
                null);
        addDirFiles(entries,
                List.of(new File("/storage/emulated/0/Android/data/com.overdrive.app/files/sentry_events")),
                null);
        addDirFiles(entries,
                List.of(new File("/storage/emulated/0/Android/data/com.overdrive.app/files/proximity_events")),
                null);

        // Dedup by filename (mirror dirs hold the same .mp4).
        Set<String> seen = new HashSet<>(entries.size() * 2);
        List<File> unique = new ArrayList<>(entries.size());
        for (DirEntry e : entries) {
            if (seen.add(e.file.getName())) unique.add(e.file);
        }

        warmupTotal.set(unique.size());
        warmupDone.set(0);

        logger.info("Warmup starting: " + unique.size() + " candidate files");

        // Phase 2: parallel parse + batched single-thread upsert.
        //
        // The dominant warmup cost is per-file `parse()` — sidecar JSON read +
        // metadata parse averages ~30 ms/file on the SD card. Single-thread
        // serial walk takes ~60 s on a 1844-clip library.
        //
        // Strategy: a small fixed pool parses files concurrently and pushes
        // pre-parsed Row objects into a bounded queue; one writer thread
        // drains the queue and runs the upserts inside a JDBC transaction
        // (ALL-OR-NOTHING is fine here — we re-write the meta state on
        // success). H2 single-row writes through one connection do NOT
        // benefit from parallel writers, so we keep the writer single-
        // threaded; the parser pool is what cuts wall-time.
        //
        // 4 parser threads matches the typical low-power head-unit (Adreno
        // 610 = quad-A55). More threads contend on the SD card's FUSE
        // mount; fewer leaves CPU idle.
        final int parsers = 4;
        final java.util.concurrent.BlockingQueue<Object> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(parsers * 8);
        final Object POISON = new Object();

        java.util.concurrent.atomic.AtomicInteger parsed =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // Writer: drains the queue and runs MERGE per row. We deliberately
        // keep the connection in its default autoCommit=true state instead
        // of batching N rows in one transaction. Three reasons:
        //  1) The connection is shared with query threads
        //     (queryRecordings/queryStats/queryDates/queryPlaces/queryCount)
        //     and FileObserver-driven `upsert(File)` callers. Holding an
        //     open transaction here would force every concurrent caller
        //     to either join our transaction (silent durability bug — a
        //     "saved" recording disappears on crash) or block on the
        //     `synchronized (this)` monitor for the entire warmup window.
        //  2) H2's per-row commit overhead is small (the redo log is
        //     mmap'd) — the warmup walltime is dominated by parse(), not
        //     the JDBC writes.
        //  3) Crash safety: SIGKILL during warmup leaves whatever rows
        //     made it through fully durable; the persisted
        //     warmup_state=in_progress flag keeps the next boot from
        //     fast-pathing on the partial state.
        // upsertRow is `synchronized (this)` so its executeUpdate doesn't
        // race a concurrent SELECT on the shared connection.
        Thread writer = new Thread(() -> {
            try {
                while (true) {
                    try {
                        Object item = queue.take();
                        if (item == POISON) break;
                        Row r = (Row) item;
                        if (upsertRow(r)) parsed.incrementAndGet();
                        int done = warmupDone.incrementAndGet();
                        if (done % 100 == 0) {
                            logger.info("Warmup progress: " + done + "/" + unique.size());
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t) {
                        // Catch unchecked throwables (Error, RuntimeException,
                        // ClassCastException-from-bad-queue-item) so the
                        // writer keeps draining. If the writer dies silently
                        // here, parsers block forever on the bounded queue
                        // and the daemon shutdown join (futures.get without
                        // timeout) deadlocks.
                        logger.error("Writer iteration failed: " + t.getMessage(), t);
                    }
                }
            } finally {
                // Belt-and-braces: if we exit the loop for any reason other
                // than POISON (interrupt, an Error escaping the inner
                // catch), drain any pending Row items so the parser
                // threads' queue.put() calls unblock and the parser pool
                // can shut down cleanly.
                queue.clear();
            }
        }, "RecordingsIndexWriter");
        writer.setDaemon(true);
        writer.start();

        // Parser pool: each worker pulls files from a shared iterator and
        // pushes parsed Rows into the queue. Failed parses are silently
        // dropped (parse() returns null) — same behaviour as the legacy
        // serial path.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(parsers, r -> {
                    Thread t = new Thread(r, "RecordingsIndexParser");
                    t.setDaemon(true);
                    return t;
                });
        try {
            java.util.concurrent.atomic.AtomicInteger cursor =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>(parsers);
            for (int i = 0; i < parsers; i++) {
                futures.add(pool.submit(() -> {
                    while (true) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= unique.size()) return;
                        File f = unique.get(idx);
                        try {
                            Row r = parse(f);
                            if (r != null) queue.put(r);
                            else warmupDone.incrementAndGet();  // count skipped
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable ignored) {
                            warmupDone.incrementAndGet();
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> fu : futures) {
                try { fu.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdown();
            try { queue.put(POISON); } catch (InterruptedException ignored) {}
            try { writer.join(); } catch (InterruptedException ignored) {}
        }

        long ms = System.currentTimeMillis() - t0;
        logger.info("Warmup complete: " + parsed.get() + "/" + unique.size()
                + " indexed in " + ms + "ms");
    }

    private void addDirFiles(List<DirEntry> out, List<File> dirs, String forceType) {
        if (dirs == null) return;
        StorageManager sm = StorageManager.getInstance();
        for (File dir : dirs) {
            if (dir == null) continue;
            // Use StorageManager.listMp4Files which falls back to shell ls
            // when File.listFiles() returns null (FUSE-mounted SD card under
            // daemon UID 2000 — without this the index silently misses every
            // SD-card .mp4).
            File[] files = sm.listMp4Files(dir);
            for (File f : files) {
                if (!f.isFile() || f.length() <= 0 || !f.canRead()) continue;
                out.add(new DirEntry(f, forceType));
            }
        }
    }

    /** Snapshot of warmup state for the API. */
    public WarmupSnapshot warmupState() {
        return new WarmupSnapshot(
                warmupRunning.get(),
                warmupComplete.get(),
                warmupDone.get(),
                warmupTotal.get());
    }

    /**
     * Reconcile the index against the filesystem. Walks every dir,
     * upserts unknown files, removes index rows whose mp4 is gone.
     * Backstop for FileObserver event drops on FUSE-mounted SD cards.
     * Cheap when the index is in sync — every existing row is one
     * stat() call.
     */
    public void reconcile() {
        if (!initialized) return;
        long t0 = System.currentTimeMillis();
        StorageManager sm = StorageManager.getInstance();

        Set<String> diskNames = new HashSet<>();
        scanDirNames(diskNames, sm.getAllRecordingsDirs());
        scanDirNames(diskNames, sm.getAllSurveillanceDirs());
        scanDirNames(diskNames, sm.getAllProximityDirs());

        // Three-phase walk: index enumerate → drop missing → upsert new.
        // The SELECT enumerate is synchronized so the snapshot is
        // consistent. The per-row remove()/upsert() calls re-acquire the
        // monitor themselves; we deliberately do NOT hold the lock across
        // the locateFile() stat() calls because on FUSE-mounted SD cards
        // they can each block 100-500ms, serializing every concurrent
        // queryRecordings/queryCount/queryStats request behind reconcile.
        // Accept temporary inconsistency — a file deleted between the
        // collect and verify phases will be cleaned up on the next
        // periodic reconcile (the operation is idempotent).
        int removed = 0;
        int added = 0;
        // Files seen by scanDirNames() (exists, readable, size>0) but not
        // findable by locateFile() — usually means a mount path drift or a
        // symlinked dir that scanDirNames() followed but locateFile()'s
        // canonical dir list does not. Surfaced in the summary so operators
        // can spot data-loss-shaped gaps instead of silent skips.
        int unlocatable = 0;

        // Phase 1: snapshot the index under the monitor.
        Set<String> indexNames = new HashSet<>();
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT filename FROM recordings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) indexNames.add(rs.getString(1));
            } catch (Exception e) {
                logger.warn("reconcile: index enumerate failed: " + e.getMessage());
                return;
            }
        }

        // Phase 2: drop rows whose file is gone. remove() takes the
        // monitor per call; that's fine — we want short critical sections
        // here so query threads can interleave.
        for (String name : indexNames) {
            if (!diskNames.contains(name)) {
                if (remove(name)) removed++;
            }
        }

        // Phase 3: upsert anything not already known. locateFile() is
        // intentionally OUTSIDE any synchronized block — its stat() calls
        // can stall on FUSE mounts and would otherwise serialize queries.
        for (String name : diskNames) {
            if (!indexNames.contains(name)) {
                File f = locateFile(name, sm);
                if (f != null) {
                    if (upsert(f)) added++;
                } else {
                    unlocatable++;
                    logger.warn("reconcile: file seen on disk but not locatable: " + name);
                }
            }
        }
        long ms = System.currentTimeMillis() - t0;
        if (added > 0 || removed > 0 || unlocatable > 0) {
            logger.info("Reconcile: +" + added + " / -" + removed
                    + (unlocatable > 0 ? " / unlocatable=" + unlocatable : "")
                    + " in " + ms + "ms");
        }
    }

    private void scanDirNames(Set<String> out, List<File> dirs) {
        StorageManager sm = StorageManager.getInstance();
        for (File dir : dirs) {
            if (dir == null) continue;
            // Same FUSE-fallback as addDirFiles — listMp4Files routes through
            // shell ls when listFiles() returns null on SD-card mounts.
            File[] files = sm.listMp4Files(dir);
            for (File f : files) {
                if (f.isFile() && f.length() > 0) out.add(f.getName());
            }
        }
    }

    private File locateFile(String name, StorageManager sm) {
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, name);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, name);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, name);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        return null;
    }

    // =================================================================
    // Queries
    // =================================================================

    /**
     * Filter spec for {@link #queryRecordings} and {@link #queryCount}.
     * Mirrors the existing /api/recordings query params 1:1 so the API
     * handler can pass through.
     */
    public static final class Filter {
        /**
         * "normal" / "sentry" / "proximity" / "oemDashcam" / null=all.
         * For multi-type queries (e.g. native fragment's Dashcam segment
         * which wants NORMAL + PROXIMITY together) use {@link #types}
         * instead — when {@code types} is non-empty it takes precedence
         * and {@code type} is ignored.
         */
        public String type;
        /**
         * Multi-type filter. When non-empty, supersedes {@link #type}.
         * Each entry is a literal type tag matched as-is. Note: "normal"
         * does NOT auto-include "oemDashcam" in this mode — callers must
         * pass both explicitly. (The single-type {@link #type} path keeps
         * the auto-include for backward compat with web clients.)
         */
        public java.util.Set<String> types;
        /** "yyyy-MM-dd" local; null = no date narrowing. */
        public String date;
        /** lowercase class names ("person", "vehicle", ...). Empty = no narrowing. */
        public Set<String> classes;
        /** "ALERT" / "CRITICAL". Empty = no narrowing. */
        public Set<String> severities;
        /** "VERY_CLOSE" / "CLOSE" / "MID" / "FAR". Empty = no narrowing. */
        public Set<String> proximities;
        /** Lowercase short label. null = no narrowing. */
        public String place;
        /**
         * Free-text place substring search. Lowercase. When non-null,
         * matches any clip whose place_short OR place_medium contains
         * this substring (case-insensitive). Stacks with {@link #place}:
         * if both are set, both must match — the exact-match wins
         * effectively because it's a stricter filter. Use cases:
         *  - "show me anything in Bay" → "Marina Bay" + "Bay City" both match.
         *  - autocomplete-style typing → "Che" matches "Cheras" before the
         *    user finishes typing.
         */
        public String placeContains;
        /**
         * ISO 3166-1 alpha-2 country code, lowercased. null = no narrowing.
         * Useful for cross-border travel logs ("everything in Malaysia").
         * Indexed via place_country column.
         */
        public String country;
        /**
         * Physical-volume filter: "INTERNAL" / "SD_CARD" / "USB". Empty/null =
         * no narrowing (show clips from every storage location, which is the
         * default — the index already spans internal + SD + USB). Matched
         * against the indexed {@code storage} column. Legacy rows whose column
         * is still NULL (written before schema v2) are matched by deriving the
         * volume from {@code abs_path} in SQL so the filter stays correct
         * before the lazy backfill completes.
         */
        public Set<String> storages;
    }

    /**
     * Page through the index with the given filter, sorted by ts_ms DESC.
     * Returns JSON rows in the same shape as the legacy
     * RecordingsApiHandler.parseRecordingUncached output so existing
     * clients don't break.
     */
    public synchronized List<JSONObject> queryRecordings(Filter f, int limit, int offset) {
        if (!initialized) return new ArrayList<>();
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(f, where, args);

        String sql = "SELECT * FROM recordings"
                + (where.length() > 0 ? " WHERE " + where : "")
                + " ORDER BY ts_ms DESC"
                + " LIMIT ? OFFSET ?";

        List<JSONObject> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int p = 1;
            for (Object a : args) bind(ps, p++, a);
            ps.setInt(p++, Math.max(1, limit));
            ps.setInt(p, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rowToJson(rs));
            }
        } catch (Exception e) {
            logger.warn("queryRecordings failed: " + e.getMessage());
        }
        return out;
    }

    public synchronized int queryCount(Filter f) {
        if (!initialized) return 0;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(f, where, args);
        String sql = "SELECT COUNT(*) FROM recordings"
                + (where.length() > 0 ? " WHERE " + where : "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int p = 1;
            for (Object a : args) bind(ps, p++, a);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            logger.warn("queryCount failed: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Distinct place_short values, top-N by count. Used by the chip row.
     * Filter applies to everything EXCEPT place_short itself — chips
     * are scoped by the surrounding type/date/class context but not
     * narrowed by the user's already-active place selection (otherwise
     * the row would always show only the active chip).
     */
    public synchronized List<PlaceBucket> queryPlaces(Filter f, int limit) {
        if (!initialized) return new ArrayList<>();
        Filter copy = copyFilter(f);
        copy.place = null;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(copy, where, args);
        if (where.length() > 0) where.append(" AND ");
        where.append("place_short IS NOT NULL");

        String sql = "SELECT place_short, COUNT(*) AS c, MAX(ts_ms) AS newest FROM recordings"
                + " WHERE " + where
                + " GROUP BY place_short"
                + " ORDER BY c DESC, place_short ASC"
                + " LIMIT ?";

        List<PlaceBucket> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int p = 1;
            for (Object a : args) bind(ps, p++, a);
            ps.setInt(p, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlaceBucket b = new PlaceBucket();
                    b.label = rs.getString(1);
                    b.count = rs.getInt(2);
                    b.newestTs = rs.getLong(3);
                    out.add(b);
                }
            }
        } catch (Exception e) {
            logger.warn("queryPlaces failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Distinct dates (yyyy-MM-dd) plus per-date count and a hasSentry
     * flag for the calendar dot decoration.
     */
    public synchronized List<DateBucket> queryDates() {
        if (!initialized) return new ArrayList<>();
        String sql =
            "SELECT ymd, COUNT(*) AS c, "
            + " MAX(CASE WHEN type = 'sentry' THEN 1 ELSE 0 END) AS hasSentry"
            + " FROM recordings WHERE ymd IS NOT NULL GROUP BY ymd";
        List<DateBucket> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DateBucket b = new DateBucket();
                b.date = rs.getString(1);
                b.count = rs.getInt(2);
                b.hasSentry = rs.getInt(3) > 0;
                out.add(b);
            }
        } catch (Exception e) {
            logger.warn("queryDates failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Per-type aggregate stats: count, total bytes, count-from-today.
     * Used by /api/recordings/stats and the native fragment header.
     */
    public synchronized Stats queryStats() {
        Stats s = new Stats();
        if (!initialized) return s;
        long todayStart = startOfTodayMillis();
        String sql =
            "SELECT type,"
            + "  COUNT(*) AS c,"
            + "  COALESCE(SUM(size_bytes), 0) AS bytes,"
            + "  SUM(CASE WHEN ts_ms >= ? THEN 1 ELSE 0 END) AS todayC"
            + " FROM recordings GROUP BY type";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, todayStart);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    long c = rs.getLong(2);
                    long b = rs.getLong(3);
                    long tc = rs.getLong(4);
                    if ("normal".equals(t) || "oemDashcam".equals(t)) {
                        s.normalCount += c; s.normalBytes += b; s.normalToday += tc;
                    } else if ("sentry".equals(t)) {
                        s.sentryCount = c; s.sentryBytes = b; s.sentryToday = tc;
                    } else if ("proximity".equals(t)) {
                        s.proximityCount = c; s.proximityBytes = b; s.proximityToday = tc;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("queryStats failed: " + e.getMessage());
        }
        return s;
    }

    // =================================================================
    // Internal — filter builder
    // =================================================================

    private static void buildWhere(Filter f, StringBuilder where, List<Object> args) {
        if (f == null) return;
        if (f.types != null && !f.types.isEmpty()) {
            // Multi-type path: literal IN(...) — caller is explicit about
            // which types to include. No auto-folding (single-type path
            // still folds "normal" → ["normal","oemDashcam"] for compat).
            appendAnd(where, "type IN " + inList(f.types.size()));
            for (String t : f.types) args.add(t);
        } else if (f.type != null && !f.type.isEmpty()) {
            // Single-type path: "normal" includes oemDashcam by convention
            // (AVM cam_* + OEM dvr_* both belong to the dashcam segment in
            // the web UI).
            if ("normal".equals(f.type)) {
                appendAnd(where, "type IN ('normal','oemDashcam')");
            } else {
                appendAnd(where, "type = ?");
                args.add(f.type);
            }
        }
        if (f.date != null && !f.date.isEmpty()) {
            appendAnd(where, "ymd = ?");
            args.add(f.date);
        }
        if (f.severities != null && !f.severities.isEmpty()) {
            appendAnd(where, "peak_severity IN " + inList(f.severities.size()));
            for (String s : f.severities) args.add(s);
        }
        if (f.proximities != null && !f.proximities.isEmpty()) {
            appendAnd(where, "peak_proximity IN " + inList(f.proximities.size()));
            for (String p : f.proximities) args.add(p);
        }
        if (f.classes != null && !f.classes.isEmpty()) {
            // actor_classes is a CSV; "person,vehicle". Match any.
            // The H2 LIKE pattern '%,X,%' against ','+col+',' is
            // standard for this case and uses the index range scan
            // when combined with another filter. For pure-class
            // queries it's a table scan, which is fine at the
            // ~1000-row scale we're targeting.
            StringBuilder clause = new StringBuilder("(");
            int i = 0;
            for (String c : f.classes) {
                if (i++ > 0) clause.append(" OR ");
                clause.append("',' || actor_classes || ',' LIKE ?");
                args.add("%," + c + ",%");
            }
            clause.append(")");
            appendAnd(where, clause.toString());
        }
        if (f.place != null && !f.place.isEmpty()) {
            appendAnd(where, "LOWER(place_short) = ?");
            args.add(f.place.toLowerCase(Locale.US));
        }
        if (f.placeContains != null && !f.placeContains.isEmpty()) {
            // Substring match across short + medium so "Bay" hits both
            // "Marina Bay" (short) and "Marina Bay, Singapore" (medium).
            // No anchored prefix optimization — at typical library sizes
            // the index's row count is the bound, not the column scan.
            appendAnd(where, "(LOWER(COALESCE(place_short, '')) LIKE ?"
                           + " OR LOWER(COALESCE(place_medium, '')) LIKE ?"
                           + " OR LOWER(COALESCE(place_display, '')) LIKE ?)");
            String pat = "%" + f.placeContains.toLowerCase(Locale.US) + "%";
            args.add(pat);
            args.add(pat);
            args.add(pat);
        }
        if (f.country != null && !f.country.isEmpty()) {
            appendAnd(where, "LOWER(place_country) = ?");
            args.add(f.country.toLowerCase(Locale.US));
        }
        if (f.storages != null && !f.storages.isEmpty()) {
            // Primary match is the indexed `storage` column. For legacy rows
            // whose column is still NULL (pre-v2, not yet backfilled), derive
            // the volume from abs_path so the filter is correct immediately:
            //   - under the internal base or /storage/emulated → INTERNAL
            //   - any other /storage/ or /mnt/ subtree            → SD_CARD
            // (USB can't be told apart from SD by path alone without the live
            // mount root, so NULL-row USB clips surface under SD_CARD until the
            // backfill stamps the real column — an acceptable transient for the
            // rare legacy-USB case; fresh rows are always exact.)
            StringBuilder clause = new StringBuilder("(");
            clause.append("storage IN ").append(inList(f.storages.size()));
            for (String s : f.storages) args.add(s);
            boolean wantInternal = f.storages.contains("INTERNAL");
            boolean wantSd = f.storages.contains("SD_CARD");
            if (wantInternal) {
                clause.append(" OR (storage IS NULL AND (abs_path LIKE '/storage/emulated/%'"
                        + " OR abs_path LIKE '/storage/emulated/0/Overdrive/%'))");
            }
            if (wantSd) {
                clause.append(" OR (storage IS NULL AND abs_path NOT LIKE '/storage/emulated/%'"
                        + " AND (abs_path LIKE '/storage/%' OR abs_path LIKE '/mnt/%'))");
            }
            clause.append(")");
            appendAnd(where, clause.toString());
        }
    }

    private static void appendAnd(StringBuilder sb, String clause) {
        if (sb.length() > 0) sb.append(" AND ");
        sb.append(clause);
    }

    // Pre-built '(?,?,...)' patterns for the common cardinalities we hit
    // (severities ≤ 2, proximities ≤ 4, types ≤ 4, classes ≤ 5). Avoids a
    // StringBuilder per filtered query — small, but every queryRecordings()
    // with a non-empty IN-set used to allocate one.
    private static final String[] IN_LIST_CACHE = buildInListCache(10);

    private static String[] buildInListCache(int max) {
        String[] cache = new String[max + 1];
        cache[0] = "()";
        for (int n = 1; n <= max; n++) {
            StringBuilder sb = new StringBuilder(2 + n * 2);
            sb.append('(');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append('?');
            }
            sb.append(')');
            cache[n] = sb.toString();
        }
        return cache;
    }

    private static String inList(int n) {
        if (n >= 0 && n < IN_LIST_CACHE.length) return IN_LIST_CACHE[n];
        StringBuilder sb = new StringBuilder(2 + n * 2);
        sb.append('(');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    private static void bind(PreparedStatement ps, int idx, Object v) throws Exception {
        if (v instanceof String) ps.setString(idx, (String) v);
        else if (v instanceof Long) ps.setLong(idx, (Long) v);
        else if (v instanceof Integer) ps.setInt(idx, (Integer) v);
        else if (v instanceof Double) ps.setDouble(idx, (Double) v);
        else ps.setObject(idx, v);
    }

    private static Filter copyFilter(Filter f) {
        Filter c = new Filter();
        if (f == null) return c;
        c.type = f.type; c.date = f.date; c.place = f.place;
        c.placeContains = f.placeContains;
        c.country = f.country;
        c.types = f.types == null ? null : new HashSet<>(f.types);
        c.classes = f.classes == null ? null : new HashSet<>(f.classes);
        c.severities = f.severities == null ? null : new HashSet<>(f.severities);
        c.proximities = f.proximities == null ? null : new HashSet<>(f.proximities);
        c.storages = f.storages == null ? null : new HashSet<>(f.storages);
        return c;
    }

    // =================================================================
    // Parsing
    // =================================================================

    /**
     * Parse one mp4 (+ its sidecar if present) into an indexable Row.
     * Returns null if the filename isn't recognised (e.g. a stray .mp4
     * we don't own).
     */
    private static Row parse(File mp4) {
        String name = mp4.getName();
        Row r = new Row();
        r.filename = name;
        r.absPath = mp4.getAbsolutePath();
        r.sizeBytes = mp4.length();
        r.mp4Mtime = mp4.lastModified();
        // Physical volume the clip lives on, classified from its path. Stored
        // as an indexed column so the recordings library can filter by volume
        // (INTERNAL / SD_CARD / USB). Best-effort — a classifier/singleton
        // failure leaves storage NULL and rowToJson falls back to deriving the
        // tag from the path at read time, so the row is never dropped.
        try {
            r.storage = com.overdrive.app.storage.StorageManager
                    .getInstance().classifyStorageForPath(r.absPath);
        } catch (Throwable ignored) {
            r.storage = null;
        }

        // Type + timestamp from filename pattern.
        Matcher cam = CAM_PATTERN.matcher(name);
        Matcher event = EVENT_PATTERN.matcher(name);
        Matcher prox = PROXIMITY_PATTERN.matcher(name);
        Matcher dvr = DVR_PATTERN.matcher(name);
        try {
            if (event.matches()) {
                r.type = "sentry";
                r.tsMs = FMT_FILENAME.get().parse(event.group(1) + "_" + event.group(2)).getTime();
            } else if (prox.matches()) {
                r.type = "proximity";
                r.tsMs = FMT_FILENAME.get().parse(prox.group(1) + "_" + prox.group(2)).getTime();
            } else if (cam.matches()) {
                r.type = "normal";
                String camStr = cam.group(1);
                r.cameraId = camStr != null ? Integer.parseInt(camStr) : 0;
                r.tsMs = FMT_FILENAME.get().parse(cam.group(2) + "_" + cam.group(3)).getTime();
            } else if (dvr.matches()) {
                r.type = "oemDashcam";
                r.tsMs = FMT_FILENAME.get().parse(dvr.group(1) + "_" + dvr.group(2)).getTime();
            } else {
                // Unknown filename grammar — fall back to mtime, tag as
                // "normal" so it surfaces somewhere instead of vanishing.
                r.type = "normal";
                r.tsMs = mp4.lastModified();
            }
        } catch (Exception e) {
            r.tsMs = mp4.lastModified();
            if (r.type == null) r.type = "normal";
        }

        // ymd helper for the calendar dot endpoint.
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(r.tsMs);
        r.ymd = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));

        // Sidecar enrichment — same logic as
        // RecordingsApiHandler.parseRecordingUncached, kept close to
        // identical so existing callers don't notice the swap.
        File sidecar = new File(mp4.getParentFile(), name.replace(".mp4", ".json"));
        if (sidecar.exists() && sidecar.canRead()) {
            r.sidecarMtime = sidecar.lastModified();
            try {
                int cap = (int) Math.min(sidecar.length(), 65536L);
                StringBuilder sb = new StringBuilder(cap);
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader(sidecar))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = br.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() >= cap) break;
                    }
                }
                JSONObject side = new JSONObject(sb.toString());
                r.schemaVersion = side.optInt("version", 2);
                JSONObject stats = side.optJSONObject("stats");
                if (stats != null) {
                    r.peakSeverity = stats.optString("peakSeverity", null);
                    r.peakProximity = stats.optString("peakProximity", null);
                    r.personCount = stats.optInt("personCount", 0);
                    r.vehicleCount = stats.optInt("vehicleCount", 0);
                    r.bikeCount = stats.optInt("bikeCount", 0);
                    r.animalCount = stats.optInt("animalCount", 0);
                    if (r.peakSeverity != null && r.peakSeverity.isEmpty()) r.peakSeverity = null;
                    if (r.peakProximity != null && r.peakProximity.isEmpty()) r.peakProximity = null;
                }
                String hero = side.optString("heroThumbnail", null);
                if (hero != null && !hero.isEmpty()) r.heroThumb = hero;

                JSONArray actors = side.optJSONArray("actors");
                if (actors != null && actors.length() > 0) {
                    StringBuilder cls = new StringBuilder();
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < actors.length(); i++) {
                        JSONObject a = actors.optJSONObject(i);
                        if (a == null) continue;
                        String c = a.optString("class", "").toLowerCase(Locale.US);
                        if (!c.isEmpty() && seen.add(c)) {
                            if (cls.length() > 0) cls.append(',');
                            cls.append(c);
                        }
                    }
                    if (cls.length() > 0) r.actorClasses = cls.toString();
                }

                JSONObject geo = side.optJSONObject("geo");
                if (geo != null) {
                    JSONObject startObj = geo.optJSONObject("start");
                    if (startObj != null) {
                        if (startObj.has("lat")) r.startLat = startObj.optDouble("lat");
                        if (startObj.has("lng")) r.startLng = startObj.optDouble("lng");
                    }
                    JSONObject placeObj = geo.optJSONObject("place");
                    if (placeObj != null) {
                        String dist = placeObj.optString("district", "");
                        String city = placeObj.optString("city", "");
                        String dn = placeObj.optString("displayName", "");
                        String cc = placeObj.optString("countryCode", "");
                        String src = placeObj.optString("source", "");
                        String shortLabel = !dist.isEmpty() ? dist
                                : !city.isEmpty() ? city
                                : (!dn.isEmpty() ? dn : null);
                        String mediumLabel = (!dist.isEmpty() && !city.isEmpty() && !dist.equals(city))
                                ? (dist + ", " + city) : shortLabel;
                        if (shortLabel != null) r.placeShort = shortLabel;
                        if (mediumLabel != null) r.placeMedium = mediumLabel;
                        if (!dn.isEmpty()) r.placeDisplay = dn;
                        if (!cc.isEmpty()) r.placeCountry = cc.toLowerCase(Locale.US);
                        if (!src.isEmpty()) r.placeSource = src;
                    }
                }
            } catch (Exception se) {
                // Sidecar parse failure is non-fatal; row still indexed
                // with bare mp4 metadata.
            }
        }

        return r;
    }

    /**
     * Render a row from the index back to the JSON shape the API and
     * legacy clients expect. Mirrors parseRecordingUncached's output
     * verbatim, plus a {@code bucketLabel} field for paging-aware
     * sticky headers.
     */
    private static JSONObject rowToJson(ResultSet rs) throws Exception {
        JSONObject rec = new JSONObject();
        String name = rs.getString("filename");
        long ts = rs.getLong("ts_ms");
        rec.put("filename", name);
        String absPath = rs.getString("abs_path");
        rec.put("path", absPath);
        // Per-clip storage tag (INTERNAL / SD_CARD / USB). Makes the silent
        // SD→internal fallback (SD bridged behind USB power) visible at the
        // file level, and backs the storage filter. Prefer the indexed column
        // (populated at parse time); fall back to deriving from the path for
        // legacy rows written before the v2 column existed (column is NULL).
        // Best-effort throughout: null/unknown is simply omitted so the badge
        // degrades gracefully rather than mislabeling.
        try {
            String storage = rs.getString("storage");
            if (storage == null || storage.isEmpty()) {
                storage = com.overdrive.app.storage.StorageManager
                        .getInstance().classifyStorageForPath(absPath);
            }
            if (storage != null) rec.put("storage", storage);
        } catch (Throwable ignored) {
            // Index queries must never fail because the storage classifier
            // (or the StorageManager singleton) is unavailable in this process.
        }
        rec.put("type", rs.getString("type"));
        rec.put("cameraId", rs.getInt("camera_id"));
        rec.put("timestamp", ts);
        long size = rs.getLong("size_bytes");
        rec.put("size", size);
        rec.put("sizeFormatted", formatSize(size));

        Date d = new Date(ts);
        rec.put("date", FMT_DATE_ISO.get().format(d));
        rec.put("time", FMT_TIME_ISO.get().format(d));
        rec.put("dateFormatted", FMT_DATE_DISPLAY.get().format(d));
        rec.put("timeFormatted", FMT_TIME_DISPLAY.get().format(d));

        rec.put("videoUrl", "/video/" + name);
        rec.put("thumbnailUrl", "/thumb/" + name);

        int sv = rs.getInt("schema_version");
        if (sv > 0) rec.put("schemaVersion", sv);

        String sev = rs.getString("peak_severity");
        if (sev != null) rec.put("peakSeverity", sev);
        String prox = rs.getString("peak_proximity");
        if (prox != null) rec.put("peakProximity", prox);

        int person = rs.getInt("person_count");
        int vehicle = rs.getInt("vehicle_count");
        int bike = rs.getInt("bike_count");
        int animal = rs.getInt("animal_count");
        if (person > 0) rec.put("personCount", person);
        if (vehicle > 0) rec.put("vehicleCount", vehicle);
        if (bike > 0) rec.put("bikeCount", bike);
        if (animal > 0) rec.put("animalCount", animal);

        String hero = rs.getString("hero_thumb");
        if (hero != null) rec.put("heroThumbnailUrl", "/thumb/" + hero);

        String classes = rs.getString("actor_classes");
        if (classes != null && !classes.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String c : classes.split(",")) {
                if (c.isEmpty()) continue;
                JSONObject a = new JSONObject();
                a.put("class", c);
                arr.put(a);
            }
            rec.put("actors", arr);
        }

        String pShort = rs.getString("place_short");
        if (pShort != null) {
            JSONObject place = new JSONObject();
            place.put("short", pShort);
            String pMed = rs.getString("place_medium");
            if (pMed != null) place.put("medium", pMed);
            String pDisp = rs.getString("place_display");
            if (pDisp != null) place.put("displayName", pDisp);
            String pCC = rs.getString("place_country");
            if (pCC != null) place.put("countryCode", pCC);
            String pSrc = rs.getString("place_source");
            if (pSrc != null) place.put("source", pSrc);
            rec.put("place", place);
        }

        double sLat = rs.getDouble("start_lat");
        if (!rs.wasNull()) rec.put("startLat", sLat);
        double sLng = rs.getDouble("start_lng");
        if (!rs.wasNull()) rec.put("startLng", sLng);

        // bucketLabel — used by the native fragment for sticky time-of-day
        // headers without needing the full list. Format: "Today", "Yesterday",
        // or "MMM d, yyyy" — matches RecordingSectionHeaderDecoration.
        rec.put("bucketLabel", bucketLabelFor(ts));
        rec.put("ymd", rs.getString("ymd"));

        return rec;
    }

    private static String bucketLabelFor(long ts) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long todayStart = today.getTimeInMillis();
        long yStart = todayStart - 86400000L;
        if (ts >= todayStart) return "Today";
        if (ts >= yStart) return "Yesterday";
        return FMT_DATE_DISPLAY.get().format(new Date(ts));
    }

    private static long startOfTodayMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(Locale.US, "%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }

    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_ISO =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_ISO =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_DISPLAY =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM d, yyyy", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_DISPLAY =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("h:mm a", Locale.US));

    private static void setNullableString(PreparedStatement ps, int idx, String v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    // =================================================================
    // POJOs
    // =================================================================

    /** Internal row representation — pre-DB and post-DB share the shape. */
    private static final class Row {
        String filename;
        String absPath;
        String type;
        int cameraId;
        long tsMs;
        long sizeBytes;
        long mp4Mtime;
        long sidecarMtime;
        int schemaVersion;
        String peakSeverity;
        String peakProximity;
        int personCount;
        int vehicleCount;
        int bikeCount;
        int animalCount;
        String heroThumb;
        String actorClasses;
        String placeShort;
        String placeMedium;
        String placeDisplay;
        String placeCountry;
        String placeSource;
        Double startLat;
        Double startLng;
        String ymd;
        String storage;   // "INTERNAL" / "SD_CARD" / "USB" / null
    }

    private static final class DirEntry {
        final File file;
        final String forceType;
        DirEntry(File f, String t) { this.file = f; this.forceType = t; }
    }

    public static final class WarmupSnapshot {
        public final boolean running;
        public final boolean complete;
        public final int done;
        public final int total;
        public WarmupSnapshot(boolean r, boolean c, int d, int t) {
            this.running = r; this.complete = c; this.done = d; this.total = t;
        }
    }

    public static final class PlaceBucket {
        public String label;
        public int count;
        public long newestTs;
    }

    public static final class DateBucket {
        public String date;
        public int count;
        public boolean hasSentry;
    }

    public static final class Stats {
        public long normalCount, normalBytes, normalToday;
        public long sentryCount, sentryBytes, sentryToday;
        public long proximityCount, proximityBytes, proximityToday;
        public long totalCount() { return normalCount + sentryCount + proximityCount; }
        public long totalBytes() { return normalBytes + sentryBytes + proximityBytes; }
        public long totalToday() { return normalToday + sentryToday + proximityToday; }
    }
}
