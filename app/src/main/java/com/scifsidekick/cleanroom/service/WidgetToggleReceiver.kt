package com.scifsidekick.cleanroom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scifsidekick.cleanroom.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the widget's on/off button. Kept out of [StatusWidgetProvider] because that class must
 * be exported (the launcher delivers its updates), while this one is not: only the widget's own
 * PendingIntent can reach it, so another app cannot flip forwarding with a crafted broadcast.
 * Same direct [SidekickRepository.setForwarding] flip as [ForwardingTileService].
 */
class WidgetToggleReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_TOGGLE) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                toggle(context)
                StatusWidgetProvider.requestUpdate(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun toggle(context: Context) {
        val graph = AppGraph.from(context)
        val enabledNow = graph.database.stateDao().get()?.enabled == true
        graph.repository.setForwarding(!enabledNow)
        // Turning it on needs the service actually running -- see ForwardingTileService's own
        // onClick doc comment for the fuller reasoning behind this exact fallback shape.
        if (!enabledNow) {
            val failure = runCatching { ForwardingService.start(context) }.exceptionOrNull()
            if (failure != null) {
                graph.repository.setForwarding(false)
                graph.repository.recordServiceEvent(
                    "Widget activation failed; switch reverted to off: ${failure.message}",
                )
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.scifsidekick.cleanroom.action.WIDGET_TOGGLE"
    }
}
