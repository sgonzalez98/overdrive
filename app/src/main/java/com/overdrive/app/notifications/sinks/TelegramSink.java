package com.overdrive.app.notifications.sinks;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.telegram.TelegramNotifier;

/**
 * Bridges {@link NotificationBus} vehicle events to Telegram.
 *
 * <p>Before this sink, charging / door / tyre / battery-health events reached
 * Web Push only (PushSink) — a user paired to Telegram but without a browser
 * subscription got nothing for, say, a charging fault. This sink closes that
 * gap by forwarding the relevant events to {@link TelegramNotifier}.
 *
 * <h3>What is and isn't forwarded (regression guards)</h3>
 * <ul>
 *   <li><b>surveillance.*</b> is EXCLUDED — surveillance motion (the two-stage
 *       {@code sendRichMotionNotifications}/{@code sendFinalTelegramNotification}
 *       flow) and proximity ({@code ProximityRecordingHandler}) already deliver
 *       to Telegram directly. Forwarding them here too would DOUBLE-SEND.</li>
 *   <li>Only <b>WARN</b> and <b>CRITICAL</b> severities are forwarded. Routine
 *       INFO events (charging started/stopped, door closed) would be Telegram
 *       spam — they stay Web-Push-only. WARN/CRITICAL (charging full/fault,
 *       door opened, tyre alarm/leak, SOH mismatch) are the ones worth a chat
 *       message.</li>
 * </ul>
 *
 * <p>Delivery rides the {@code CRITICAL} Telegram category (the {@code
 * criticalAlerts} toggle, default ON), so a user can silence vehicle alerts by
 * turning critical alerts off — the same control surface the rest of the
 * critical path uses. {@code TelegramNotifier} applies its own fresh category
 * gate and spools on daemon-down, so a vehicle alert fired while the bot is
 * down (e.g. parked charging fault) is delivered when it next starts.
 */
public final class TelegramSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TelegramSink");

    @Override
    public void onNotification(NotificationEvent event) {
        if (event == null) return;
        try {
            // Already delivered to Telegram via the direct path — don't dup.
            if (event.category != null && event.category.startsWith("surveillance.")) {
                return;
            }
            // INFO is Web-Push-only; only escalate WARN/CRITICAL to Telegram.
            if (event.severity == NotificationEvent.Severity.INFO) {
                return;
            }

            String icon = event.severity == NotificationEvent.Severity.CRITICAL ? "🚨" : "⚠️";
            StringBuilder msg = new StringBuilder();
            msg.append(icon).append(" *").append(md(event.title)).append("*");
            if (event.body != null && !event.body.isEmpty()) {
                msg.append("\n").append(md(event.body));
            }
            // CRITICAL category → criticalAlerts gate (default ON). sendMessage
            // is async + spools on daemon-down for the CRITICAL category.
            TelegramNotifier.sendMessage(msg.toString(), "CRITICAL");
        } catch (Throwable t) {
            logger.warn("TelegramSink forward failed: " + t.getMessage());
        }
    }

    /**
     * Escape legacy-Markdown metacharacters. The daemon's sendMessage hardcodes
     * parse_mode=Markdown, and these titles/bodies carry user-set content
     * (e.g. a safe-zone name like "Home_Garage") — an unescaped {@code _} would
     * 400 ("can't parse entities") and silently drop the whole message. Matches
     * the daemon-side mdEscape character set.
     */
    private static String md(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }
}
