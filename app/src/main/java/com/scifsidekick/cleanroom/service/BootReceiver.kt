package com.scifsidekick.cleanroom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scifsidekick.cleanroom.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val failure = runCatching { ForwardingService.start(context) }.exceptionOrNull() ?: return
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    AppGraph.from(context).repository.recordServiceEvent(
                        "Service could not restart after ${intent.action}: ${failure.message}",
                    )
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
