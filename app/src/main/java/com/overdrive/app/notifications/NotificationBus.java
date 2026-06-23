package com.overdrive.app.notifications;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-process pub/sub for {@link NotificationEvent}s. Mirrors the shape of
 * {@code TelegramEventBus} so the patterns line up.
 *
 * <p>v1 is in-process only (publishers and sinks both live in CameraDaemon).
 * If a future emit source lives in another process, an
 * {@code NotificationIpcServer} can sit in front of this and forward.
 */
public final class NotificationBus {

    public interface Sink {
        void onNotification(NotificationEvent event);
    }

    private static final NotificationBus INSTANCE = new NotificationBus();

    /**
     * Max events buffered while NO sink is subscribed yet (the startup window
     * between the vehicle notifiers starting and the async notifications-init
     * subscribing PushSink/TelegramSink). A real hardware edge (charging fault,
     * door opened) in that window would otherwise be dropped silently — upstream
     * of the IPC spool, so daemon-down spooling can't recover it. Small + bounded
     * (these are rare boot-window edges); flushed to the first sink on subscribe.
     */
    private static final int PRESUBSCRIBE_BUFFER_MAX = 16;

    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    // Guarded by itself. Only ever holds events published before the first
    // subscribe(); emptied (and never refilled) once a sink exists.
    private final java.util.ArrayDeque<NotificationEvent> preSubscribeBuffer =
            new java.util.ArrayDeque<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NotificationBus");
        t.setDaemon(true);
        return t;
    });

    private NotificationBus() {}

    public static NotificationBus get() { return INSTANCE; }

    public void subscribe(Sink sink) {
        if (sink == null || sinks.contains(sink)) return;
        sinks.add(sink);
        // Flush any events buffered before the first sink existed, so a vehicle
        // edge that fired during the startup window still reaches this sink.
        java.util.List<NotificationEvent> pending = null;
        synchronized (preSubscribeBuffer) {
            if (!preSubscribeBuffer.isEmpty()) {
                pending = new java.util.ArrayList<>(preSubscribeBuffer);
                preSubscribeBuffer.clear();
            }
        }
        if (pending != null) {
            for (NotificationEvent e : pending) publish(e);
        }
    }

    public void unsubscribe(Sink sink) {
        sinks.remove(sink);
    }

    public void publish(NotificationEvent event) {
        if (event == null) return;
        // No sink yet: buffer (bounded) instead of dropping, so a boot-window
        // hardware edge isn't lost. Once a sink subscribes the buffer is flushed
        // and this branch is never taken again.
        if (sinks.isEmpty()) {
            synchronized (preSubscribeBuffer) {
                // Re-check under lock: a sink may have appeared between the
                // isEmpty() test and here; if so, fall through to normal dispatch.
                if (sinks.isEmpty()) {
                    if (preSubscribeBuffer.size() >= PRESUBSCRIBE_BUFFER_MAX) {
                        NotificationEvent dropped = preSubscribeBuffer.pollFirst();
                        System.err.println("NotificationBus: pre-subscribe buffer full, dropped oldest: "
                                + (dropped != null ? dropped.category : "?"));
                    }
                    preSubscribeBuffer.addLast(event);
                    return;
                }
            }
        }
        try {
            executor.execute(() -> {
                for (Sink s : sinks) {
                    try {
                        s.onNotification(event);
                    } catch (Throwable t) {
                        // never let one sink kill the others
                        System.err.println("NotificationBus: sink error: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            // RejectedExecutionException at shutdown — same defensive style as TelegramEventBus
            System.err.println("NotificationBus: publish failed: " + t.getMessage());
        }
    }
}
