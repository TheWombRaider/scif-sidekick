package com.scifsidekick.cleanroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the manifest's exported surface: an exported component is reachable by every app on the
 * device, so only the ones that must be exported may be, and none may change forwarding state.
 */
class ManifestExportTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    // One chunk per <activity>/<service>/<receiver>, keyed by its android:name.
    private val components: Map<String, String> =
        Regex("<(?:activity|service|receiver)\\s")
            .split(manifest)
            .drop(1)
            .associateBy { chunk -> Regex("android:name=\"([^\"]+)\"").find(chunk)!!.groupValues[1] }

    private fun isExported(name: String): Boolean =
        components.getValue(name).substringBefore(">").contains("android:exported=\"true\"")

    @Test
    fun widgetToggleReceiverIsNotExported() {
        assertFalse(isExported(".service.WidgetToggleReceiver"))
        assertFalse(components.getValue(".service.WidgetToggleReceiver").contains("<intent-filter"))
    }

    @Test
    fun widgetProviderDoesNotChangeForwardingState() {
        val provider = File("src/main/java/com/scifsidekick/cleanroom/service/StatusWidgetProvider.kt").readText()
        assertFalse(provider.contains("setForwarding"))
    }

    @Test
    fun onlyTheKnownComponentsAreExported() {
        val exported = components.keys.filter(::isExported).toSet()
        assertEquals(
            setOf(
                ".ui.MainActivity",
                ".service.ForwardingTileService",
                ".messaging.IncomingMessageReceiver",
                ".service.StatusWidgetProvider",
            ),
            exported,
        )
    }
}
