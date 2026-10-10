package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.AlertNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class NotificationIdsTest {
    /** Every `*_NOTIFICATION_ID` constant AlertNotifier declares, by name. */
    private val ids: Map<String, Int> =
        AlertNotifier::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.endsWith("_NOTIFICATION_ID") }
            .associate { it.name to it.getInt(null) }

    @Test fun `every notification id the app posts under is distinct`() {
        assertTrue("found ${ids.keys}", ids.keys.containsAll(listOf("FOREGROUND_NOTIFICATION_ID", "CIRCUIT_NOTIFICATION_ID", "DELIVERY_REVIEW_NOTIFICATION_ID")))
        assertEquals("duplicate ids in $ids", ids.size, ids.values.toSet().size)
    }

    @Test fun `each provider's reconnect alert has its own declared id and Gmail keeps 4103`() {
        val gmail = AlertNotifier.authorizationNotificationId("gmail")
        val graph = AlertNotifier.authorizationNotificationId("graph")
        val other = AlertNotifier.authorizationNotificationId("router")
        assertEquals(4103, gmail)
        assertEquals(3, setOf(gmail, graph, other).size)
        assertTrue(ids.values.containsAll(listOf(gmail, graph, other)))
        assertTrue(listOf(gmail, graph, other).none { it == AlertNotifier.DELIVERY_REVIEW_NOTIFICATION_ID || it == AlertNotifier.FOREGROUND_NOTIFICATION_ID || it == AlertNotifier.CIRCUIT_NOTIFICATION_ID })
    }
}
