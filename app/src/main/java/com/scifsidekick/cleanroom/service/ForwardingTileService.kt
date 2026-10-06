package com.scifsidekick.cleanroom.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: a fast on/off for the master forwarding switch that never requires
 * opening the app or the notification shade. Added directly in response to the persistent
 * foreground-service notification being impossible to swipe away while forwarding runs (Android
 * requires that of any foreground service, not a choice this app made) -- this tile is the
 * alternative surface for the thing people actually reach for the notification to do: check
 * whether forwarding is on, and flip it off or back on.
 *
 * Deliberately a direct toggle of [SidekickRepository.setForwarding], not a copy of
 * [MainViewModel.setForwarding]'s richer pre-flight checks (filters configured, SMS permissions,
 * Gmail connected) -- a tile has no good surface for a multi-condition validation message, and
 * every one of those preconditions already fails safe on its own if unmet (see this class's
 * onClick doc). The tile is a pause/resume control for an already-configured setup, not a setup
 * wizard.
 */
class ForwardingTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val graph = AppGraph.from(applicationContext)
            val enabledNow = graph.database.stateDao().get()?.enabled == true
            graph.repository.setForwarding(!enabledNow)
            // Turning it on needs the service actually running -- flipping the DB flag alone
            // does nothing if the process was fully stopped (the previous "off" tick's
            // stopSelf() in ForwardingService, or the app never having been opened this boot).
            // Turning it off needs no explicit stop call here: the service's own loop notices
            // the flag on its very next tick and stops itself -- see its doc comment for why
            // that's the one place this is handled, rather than duplicating a stop call at every
            // site (this tile included) that can flip the switch off.
            if (!enabledNow) {
                val failure = runCatching { ForwardingService.start(applicationContext) }.exceptionOrNull()
                if (failure != null) {
                    graph.repository.setForwarding(false)
                    graph.repository.recordServiceEvent(
                        "Tile activation failed; switch reverted to off: ${failure.message}",
                    )
                }
            }
            StatusWidgetProvider.requestUpdate(applicationContext)
            refresh()
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch {
            val state = AppGraph.from(applicationContext).database.stateDao().get()
            val enabled = state?.enabled == true
            val paused = enabled && state?.emailCircuitOpen == true
            val tile = qsTile ?: return@launch
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this@ForwardingTileService, R.drawable.ic_notification)
            tile.label = if (paused && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "SCIF paused" else "SCIF forwarding"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = when {
                    paused -> "Paused - open app to reset"
                    enabled -> "On"
                    else -> "Off"
                }
            }
            tile.updateTile()
        }
    }
}
