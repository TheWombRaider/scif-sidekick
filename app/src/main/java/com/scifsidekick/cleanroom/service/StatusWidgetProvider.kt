package com.scifsidekick.cleanroom.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.R
import com.scifsidekick.cleanroom.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A home-screen at-a-glance status card, the same underlying state [ForwardingTileService]
 * surfaces in Quick Settings but visible without pulling anything down. Tapping the card opens
 * the app; the toggle button is handled by [WidgetToggleReceiver], which is not exported -- this
 * class has to be, since the launcher delivers ACTION_APPWIDGET_UPDATE to it, so it must never
 * carry anything that changes forwarding state.
 *
 * Refreshed from every place forwarding's on/off or snooze state actually changes (repository,
 * watchdog, snooze re-enable) via [requestUpdate], not by polling -- [updatePeriodMillis] in
 * widget_status_info.xml is only the 30-minute floor Android enforces anyway, a fallback for
 * anything that slips through, not the primary refresh path.
 *
 * Overrides [onReceive] directly instead of the usual [AppWidgetProvider.onUpdate] hook: every
 * path here needs a real (suspend, Room-backed) database read before it has anything to render,
 * which can't complete within the few milliseconds a plain [android.content.BroadcastReceiver]
 * callback is guaranteed to keep the process alive for. [goAsync] is what buys the time for that
 * -- without it, Android is free to kill the process the instant this method returns, mid-query,
 * and the widget silently never updates.
 */
class StatusWidgetProvider : AppWidgetProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetIds =
            when (intent.action) {
                AppWidgetManager.ACTION_APPWIDGET_UPDATE -> intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                else -> {
                    // onDeleted/onEnabled/onDisabled/onAppWidgetOptionsChanged/onRestored -- none
                    // of which touch the database, so the default synchronous handling is fine.
                    super.onReceive(context, intent)
                    return
                }
            }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = appWidgetIds ?: manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
                ids.forEach { id -> refresh(context, manager, id) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun refresh(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
    ) {
        val graph = AppGraph.from(context)
        val state = graph.database.stateDao().get()
        val enabled = state?.enabled == true
        val now = System.currentTimeMillis()
        val snoozedActive = !enabled && (state?.snoozedUntilMs ?: 0L) > now
        val queued = runCatching { graph.database.queueDao().queuedCount() }.getOrDefault(0)
        val lastSentAt = runCatching { graph.database.eventLogDao().lastSentAtOnce() }.getOrNull()

        val statusText =
            when {
                snoozedActive -> "Snoozed"
                enabled -> "Forwarding ON"
                else -> "Forwarding OFF"
            }
        val detailText = "$queued queued - " + if (lastSentAt == null) "never sent" else "last sent ${formatRelativeTime(lastSentAt)}"

        val views = RemoteViews(context.packageName, R.layout.widget_status)
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextViewText(R.id.widget_detail, detailText)
        views.setTextViewText(R.id.widget_toggle, if (enabled) "Turn off" else "Turn on")

        val openAppIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_title, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_status, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_detail, openAppIntent)

        val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).setAction(WidgetToggleReceiver.ACTION_TOGGLE)
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                widgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    companion object {
        /** Same short "3m ago" idiom as the Home dashboard's status card (see MainActivity.kt's
         *  own copy) -- duplicated rather than shared because a widget-process helper pulling in
         *  ui.* is the wrong dependency direction, not because the format should ever diverge. */
        private fun formatRelativeTime(timestampMs: Long): String {
            val minutes = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 24 * 60 -> "${minutes / 60}h ago"
                else -> "${minutes / (24 * 60)}d ago"
            }
        }

        /** Pushes a fresh render to every placed instance of this widget right now, rather than
         *  waiting for Android's own (30-minute floor) update schedule. Safe to call
         *  unconditionally -- [AppWidgetManager.getAppWidgetIds] simply returns empty if the
         *  widget was never added to a home screen, and the broadcast below is a normal
         *  ACTION_APPWIDGET_UPDATE, so it lands back in this same class's [onReceive]. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, StatusWidgetProvider::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}
