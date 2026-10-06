package com.scifsidekick.cleanroom

import android.app.Application
import com.scifsidekick.cleanroom.service.GmailWatchRenewalWorker
import com.scifsidekick.cleanroom.service.HeartbeatEmailWorker
import com.scifsidekick.cleanroom.service.RemoteEnableWorker
import com.scifsidekick.cleanroom.service.WatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScifSidekickApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.from(this)
        graph.alerts.createChannels()
        scope.launch { graph.repository.ensureInitialized() }
        // ExistingPeriodicWorkPolicy.KEEP means this is safe to call unconditionally on every
        // process start -- WorkManager already persists the schedule across process death and
        // reboot on its own, so this never needs re-arming from BootReceiver too.
        WatchdogWorker.schedule(this)
        GmailWatchRenewalWorker.schedule(this)
        RemoteEnableWorker.schedule(this)
        HeartbeatEmailWorker.schedule(this)
    }
}
