package com.scifsidekick.cleanroom.service

import java.util.Locale

object ForwardLatency {
    fun describe(elapsedMs: Long): String {
        val ms = elapsedMs.coerceAtLeast(0L)
        return when {
            ms < 1_000L -> "under 1s"
            ms < 60_000L -> String.format(Locale.US, "%.1fs", ms / 1_000.0)
            ms < 3_600_000L -> "${ms / 60_000L}m ${(ms % 60_000L) / 1_000L}s"
            else -> "${ms / 3_600_000L}h ${(ms % 3_600_000L) / 60_000L}m"
        }
    }
}
