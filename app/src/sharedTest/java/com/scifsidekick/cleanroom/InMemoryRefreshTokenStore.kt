package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.graph.RefreshTokenStore

/** [RefreshTokenStore] held in memory, for tests. Records every write so rotation can be asserted. */
class InMemoryRefreshTokenStore(
    initial: String? = null,
) : RefreshTokenStore {
    @Volatile private var token: String? = initial
    val writes = mutableListOf<String>()
    var clears = 0
        private set

    /** When true, [write] throws as a failed commit or Keystore error would, and stores nothing. */
    @Volatile var failWrites = false

    @Synchronized override fun read(): String? = token

    @Synchronized override fun write(token: String) {
        if (failWrites) throw IllegalStateException("write failed")
        writes += token
        this.token = token
    }

    @Synchronized override fun clear() {
        clears++
        token = null
    }
}
