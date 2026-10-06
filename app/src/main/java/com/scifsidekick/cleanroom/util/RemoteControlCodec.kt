package com.scifsidekick.cleanroom.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single allowlist behind every remote-control-by-email command -- Compose a new text, Enable
 * forwarding, Disable forwarding, and Ask for status -- replacing what used to be four separate
 * toggle+allowlist pairs in [com.scifsidekick.cleanroom.data.AppSettingsEntity]. One master switch
 * ([com.scifsidekick.cleanroom.data.AppSettingsEntity.remoteControlEnabled]) gates all four at
 * once as a single kill switch; below that, each authorized address carries its own independent
 * per-command permission bit, so trusting an address for one command never implies trusting it for
 * another -- the same invariant the four old allowlists enforced by being physically separate,
 * just consolidated into one place to configure instead of four.
 *
 * On by default, deliberately: this app is built for personal, single-owner use, where a feature
 * that has to be discovered and switched on separately per command adds friction without adding
 * real safety -- an address still has to be deliberately added below before any command from it
 * does anything at all, so the master switch alone was never the actual authorization boundary.
 */
object RemoteControlCodec {
    data class Sender(
        val address: String,
        val canCompose: Boolean = true,
        val canEnable: Boolean = true,
        val canDisable: Boolean = true,
        val canStatus: Boolean = true,
    )

    fun toJson(senders: List<Sender>): String =
        JSONArray()
            .apply {
                senders.forEach { sender ->
                    put(
                        JSONObject().apply {
                            put("address", sender.address)
                            put("canCompose", sender.canCompose)
                            put("canEnable", sender.canEnable)
                            put("canDisable", sender.canDisable)
                            put("canStatus", sender.canStatus)
                        },
                    )
                }
            }.toString()

    // Fails safe to an empty list, same reasoning as PayloadCodec.pathsFromJson: a single row with
    // malformed JSON -- a botched migration, a manual DB edit -- must degrade to "authorizes
    // nobody," never crash the poll that reads it. An entry missing its address is dropped, not
    // fatal to the rest of the list; a missing permission field defaults to false, never true, so a
    // truncated or corrupted row can only lose capability, never silently gain it.
    fun fromJson(json: String): List<Sender> =
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val address = obj.optString("address").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                Sender(
                    address = address,
                    canCompose = obj.optBoolean("canCompose", false),
                    canEnable = obj.optBoolean("canEnable", false),
                    canDisable = obj.optBoolean("canDisable", false),
                    canStatus = obj.optBoolean("canStatus", false),
                )
            }
        }.getOrDefault(emptyList())

    /**
     * The one place every remote-email command checks its sender against this allowlist, so the
     * four commands can never drift into authorizing the same address by different rules. Callers
     * check [com.scifsidekick.cleanroom.data.AppSettingsEntity.remoteControlEnabled] themselves
     * first (see each call site's own comment) so a command email arriving while remote control is
     * entirely off is a silent no-op, not a logged "blocked" event -- those mean different things
     * and would otherwise be indistinguishable in History.
     */
    fun isAuthorized(
        sendersJson: String,
        address: String?,
        capability: (Sender) -> Boolean,
    ): Boolean {
        val canonical = ComposeAuthorization.canonicalAddress(address) ?: return false
        return fromJson(sendersJson)
            .filter(capability)
            .any { ComposeAuthorization.canonicalAddress(it.address) == canonical }
    }
}
