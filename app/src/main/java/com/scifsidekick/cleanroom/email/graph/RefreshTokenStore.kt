package com.scifsidekick.cleanroom.email.graph

/** Holds the Microsoft refresh token. Implementations never log or expose the value. */
interface RefreshTokenStore {
    /** The stored token, or null when there is none or it cannot be decrypted or was tampered with. Never throws. */
    fun read(): String?

    /** Stores [token], replacing any earlier one. Returns only once the token is persisted. */
    fun write(token: String)

    /** Forgets the token. */
    fun clear()
}
