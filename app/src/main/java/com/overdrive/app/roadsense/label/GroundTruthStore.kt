package com.overdrive.app.roadsense.label

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.roadsense.detect.DetectionCandidate
import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.Severity
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Append-only store of Calibration-Mode ground-truth labels (R-DET-7, D-025).
 *
 * When the user is in Calibration Mode and a detection fires, the overlay shows a
 * confirm card pre-filled with the algorithm's assessment; the user confirms /
 * rejects / corrects severity / corrects type. Each verdict is recorded HERE,
 * paired with the RAW detection features the algorithm saw. That pairing is the
 * labeled dataset that:
 *   - validates the detector against the G-1..G-3 accuracy gates,
 *   - tunes the provisional thresholds (severity buckets, normalization exponent),
 *   - feeds consensus with high weight (a human said so).
 *
 * Daemon-side (UID 2000), H2/JDBC — same engine + lock/recovery conventions as
 * [com.overdrive.app.roadsense.store.RoadSenseStore] and SocHistoryDatabase
 * (D-017). Append-only: we never mutate a label, we just accumulate evidence.
 *
 * Kept deliberately small — it's a research/label sink, not a hot path. Writes
 * happen at most a few times per drive (one per confirmed detection), so a plain
 * synchronized connection is fine; no scheduler/pruning (labels are precious, we
 * keep them all).
 */
class GroundTruthStore private constructor() {

    private val lock = Any()
    @Volatile private var connection: Connection? = null
    @Volatile private var initialized = false

    fun init() {
        synchronized(lock) {
            if (initialized) return
            try {
                Class.forName("org.h2.Driver")
                connection = DriverManager.getConnection(JDBC_URL)
                createTable()
                initialized = true
                logger.info("GroundTruthStore initialized at $DB_PATH")
            } catch (e: Exception) {
                logger.error("GroundTruthStore init failed: " + e.message, e)
            }
        }
    }

    private fun createTable() {
        connection?.createStatement()?.use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS roadsense_labels (" +
                    "id VARCHAR(40) PRIMARY KEY," +
                    "hazard_id VARCHAR(40)," +       // the RoadSenseStore row this labels (may be null)
                    // user verdict
                    "confirmed INT," +               // 1 = real hazard, 0 = rejected (not a hazard)
                    "user_severity INT," +           // user-corrected severity (1..3), null if unchanged
                    "user_type INT," +               // user-corrected type ordinal, null if unchanged
                    // algorithm assessment at detection time (what we predicted)
                    "algo_type INT," +
                    "algo_severity INT," +
                    "algo_confidence DOUBLE," +
                    // RAW detection features (the labeled input the detector saw)
                    "peak_up DOUBLE, peak_down DOUBLE, rise_ms INT, duration_ms INT," +
                    "dip_leading INT, speed_mps DOUBLE, axle_gap_ms INT, lateral_asym DOUBLE," +
                    // gyro-derived pitch/roll features (breaker-vs-pothole; pitch_valid gates them)
                    "peak_pitch_rate DOUBLE, peak_roll_rate DOUBLE, pitch_valid INT," +
                    "lat DOUBLE, lng DOUBLE, created_ms BIGINT);"
            )
        }
        // Migration: CREATE TABLE IF NOT EXISTS won't add columns to a pre-existing
        // table, so add the pitch/roll features idempotently for older databases.
        // H2 supports ADD COLUMN IF NOT EXISTS; a failure here must not crash init.
        try {
            connection?.createStatement()?.use { st ->
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS peak_pitch_rate DOUBLE;")
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS peak_roll_rate DOUBLE;")
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS pitch_valid INT;")
            }
        } catch (e: Exception) {
            logger.error("GroundTruthStore pitch-column migration failed: " + e.message, e)
        }
    }

    /**
     * Record one ground-truth label. [candidate] is the raw detection; the algo*
     * args are what we assessed; the user* args are the human verdict (corrections
     * null when the user accepted our value). [hazardId] links to the stored row.
     */
    fun record(
        hazardId: String?,
        candidate: DetectionCandidate,
        algoType: HazardType,
        algoSeverity: Severity,
        algoConfidence: Float,
        confirmed: Boolean,
        userSeverity: Severity?,
        userType: HazardType?,
        lat: Double,
        lng: Double,
        nowMs: Long,
    ) {
        synchronized(lock) {
            val c = connection ?: return
            try {
                c.prepareStatement(
                    "INSERT INTO roadsense_labels (id, hazard_id, confirmed, user_severity, " +
                        "user_type, algo_type, algo_severity, algo_confidence, peak_up, peak_down, " +
                        "rise_ms, duration_ms, dip_leading, speed_mps, axle_gap_ms, lateral_asym, " +
                        "peak_pitch_rate, peak_roll_rate, pitch_valid, " +
                        "lat, lng, created_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);"
                ).use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, hazardId)
                    ps.setInt(3, if (confirmed) 1 else 0)
                    if (userSeverity != null) ps.setInt(4, userSeverity.level) else ps.setNull(4, java.sql.Types.INTEGER)
                    if (userType != null) ps.setInt(5, userType.ordinal) else ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setInt(6, algoType.ordinal)
                    ps.setInt(7, algoSeverity.level)
                    ps.setDouble(8, algoConfidence.toDouble())
                    ps.setDouble(9, candidate.peakUp.toDouble())
                    ps.setDouble(10, candidate.peakDown.toDouble())
                    ps.setInt(11, candidate.riseTimeMs)
                    ps.setInt(12, candidate.durationMs)
                    ps.setInt(13, if (candidate.dipLeading) 1 else 0)
                    ps.setDouble(14, candidate.speedMps.toDouble())
                    if (candidate.axlePairGapMs != null) ps.setInt(15, candidate.axlePairGapMs!!) else ps.setNull(15, java.sql.Types.INTEGER)
                    ps.setDouble(16, candidate.lateralAsymmetry.toDouble())
                    ps.setDouble(17, candidate.peakPitchRate.toDouble())
                    ps.setDouble(18, candidate.peakRollRate.toDouble())
                    ps.setInt(19, if (candidate.pitchValid) 1 else 0)
                    ps.setDouble(20, lat)
                    ps.setDouble(21, lng)
                    ps.setLong(22, nowMs)
                    ps.executeUpdate()
                }
                logger.info("ground-truth label: confirmed=$confirmed algo=$algoType/$algoSeverity hazard=$hazardId")
            } catch (e: Exception) {
                logger.error("GroundTruthStore.record failed: " + e.message, e)
            }
        }
    }

    /** Count of labels (diagnostics / "how mature is our training set"). */
    fun count(): Int {
        synchronized(lock) {
            val c = connection ?: return 0
            return try {
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM roadsense_labels;").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            } catch (e: Exception) { 0 }
        }
    }

    /** "Delete local calibrations" also clears the ground-truth labels (R-SET-5). */
    fun deleteAll(): Int {
        synchronized(lock) {
            val c = connection ?: return -1
            return try {
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM roadsense_labels;") }
            } catch (e: Exception) { -1 }
        }
    }

    fun stop() {
        synchronized(lock) {
            try { connection?.close() } catch (_: Exception) {}
            connection = null
            initialized = false
        }
    }

    companion object {
        private val logger = DaemonLogger.getInstance("RoadSense/GroundTruth")
        private const val DB_PATH = "/data/local/tmp/overdrive_roadsense_labels_h2"
        private const val JDBC_URL =
            "jdbc:h2:file:$DB_PATH;FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE"

        @Volatile private var instance: GroundTruthStore? = null
        @JvmStatic
        fun getInstance(): GroundTruthStore =
            instance ?: synchronized(this) {
                instance ?: GroundTruthStore().also { instance = it }
            }
    }
}
