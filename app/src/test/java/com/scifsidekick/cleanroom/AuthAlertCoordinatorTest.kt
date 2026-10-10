package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.AuthAlertAction
import com.scifsidekick.cleanroom.service.AuthAlertCoordinator
import com.scifsidekick.cleanroom.service.ProviderAlertState
import com.scifsidekick.cleanroom.service.authAlertActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthAlertCoordinatorTest {
    // ------------------------------------------------------------------ the pure decision table

    private fun state(
        configured: Boolean = true,
        available: Boolean = true,
        authFailed: Boolean = false,
        alertShown: Boolean? = false,
        id: String = "graph",
        name: String = "Outlook",
    ) = ProviderAlertState(id, name, configured, available, authFailed, alertShown)

    private fun decide(
        s: ProviderAlertState,
        showUnavailable: Boolean = false,
    ) = authAlertActions(listOf(s), showUnavailable)

    @Test fun `healthy and no alert does nothing`() {
        assertEquals(emptyList<AuthAlertAction>(), decide(state()))
        assertEquals(emptyList<AuthAlertAction>(), decide(state(), showUnavailable = true))
    }

    @Test fun `healthy with a stale alert clears it`() {
        assertEquals(listOf(AuthAlertAction.Clear("graph")), decide(state(alertShown = true)))
    }

    @Test fun `unavailable shows only when asked to show unavailable members`() {
        assertEquals(emptyList<AuthAlertAction>(), decide(state(available = false)))
        assertEquals(listOf(AuthAlertAction.Show("graph", "Outlook")), decide(state(available = false), showUnavailable = true))
    }

    @Test fun `unavailable with the alert already up does nothing`() {
        assertEquals(emptyList<AuthAlertAction>(), decide(state(available = false, alertShown = true), showUnavailable = true))
        assertEquals(emptyList<AuthAlertAction>(), decide(state(available = false, alertShown = true)))
    }

    @Test fun `an unrecovered auth failure keeps the alert even while the provider reads available`() {
        assertEquals(emptyList<AuthAlertAction>(), decide(state(authFailed = true, alertShown = true)))
    }

    @Test fun `a provider that is no longer configured has its alert cleared`() {
        assertEquals(listOf(AuthAlertAction.Clear("graph")), decide(state(configured = false, available = false, alertShown = true)))
        assertEquals(emptyList<AuthAlertAction>(), decide(state(configured = false, available = false), showUnavailable = true))
    }

    @Test fun `a provider that is not configured never needs reconnecting, even with a failure on record`() {
        assertEquals(listOf(AuthAlertAction.Clear("graph")), decide(state(configured = false, authFailed = true, alertShown = true)))
        assertEquals(emptyList<AuthAlertAction>(), decide(state(configured = false, available = false, authFailed = true), showUnavailable = true))
    }

    @Test fun `an unknown alert state acts, since show and clear are both harmless to repeat`() {
        assertEquals(listOf(AuthAlertAction.Clear("graph")), decide(state(alertShown = null)))
        assertEquals(listOf(AuthAlertAction.Show("graph", "Outlook")), decide(state(available = false, alertShown = null), showUnavailable = true))
        assertEquals(emptyList<AuthAlertAction>(), decide(state(available = false, alertShown = null)))
    }

    @Test fun `providers are decided independently and in order`() {
        val actions =
            authAlertActions(
                listOf(
                    state(id = "gmail", name = "Gmail", available = false),
                    state(id = "graph", name = "Outlook", alertShown = true),
                ),
                showUnavailable = true,
            )
        assertEquals(listOf(AuthAlertAction.Show("gmail", "Gmail"), AuthAlertAction.Clear("graph")), actions)
    }

    // ------------------------------------------------------------------ the coordinator itself

    // The fake notification shade is keyed by the real notification ids, so a collision with another
    // app notification (Delivery needs review) shows up here.
    private val gmail = FakeMailTransport("gmail", "Gmail")
    private val graph = FakeMailTransport("graph", "Outlook")
    private var configured: List<MailTransport> = listOf(gmail)
    private val showing = mutableSetOf(AlertNotifier.DELIVERY_REVIEW_NOTIFICATION_ID)
    private val log = mutableListOf<String>()
    private val queried = mutableListOf<String>()

    private fun id(provider: String) = AlertNotifier.authorizationNotificationId(provider)

    private val coordinator =
        AuthAlertCoordinator(
            providers = { listOf(gmail, graph) },
            configured = { configured },
            isShowing = {
                queried += it
                id(it) in showing
            },
            show = { p, name ->
                log += "show $p $name"
                showing += id(p)
            },
            clear = { p ->
                log += "clear $p"
                showing -= id(p)
            },
        )

    @After fun theDeliveryReviewAlertIsNeverTouched() {
        assertTrue("Delivery needs review was cancelled", AlertNotifier.DELIVERY_REVIEW_NOTIFICATION_ID in showing)
    }

    private fun auth(
        id: String,
        name: String,
    ) = MailAuthRequiredException("reconnect", id, name)

    @Test fun `an auth failure shows once however often it is reported`() =
        runBlocking {
            coordinator.authRequired(auth("gmail", "Gmail"))
            coordinator.authRequired(auth("gmail", "Gmail"))
            assertEquals(listOf("show gmail Gmail"), log)
            assertEquals(setOf(AlertNotifier.DELIVERY_REVIEW_NOTIFICATION_ID, 4103), showing)
        }

    @Test fun `an auth failure shows again after the user dismissed the alert`() =
        runBlocking {
            coordinator.authRequired(auth("gmail", "Gmail"))
            showing -= id("gmail") // swiped away
            coordinator.authRequired(auth("gmail", "Gmail"))
            assertEquals(listOf("show gmail Gmail", "show gmail Gmail"), log)
        }

    @Test fun `service start alerts every configured unavailable member under its own name`() =
        runBlocking {
            configured = listOf(gmail, graph)
            gmail.signedIn = false
            graph.signedIn = false
            coordinator.serviceStarted()
            coordinator.serviceStarted()
            assertEquals(listOf("show gmail Gmail", "show graph Outlook"), log)
        }

    @Test fun `gmail only never queries, shows or clears anything but Gmail`() =
        runBlocking {
            gmail.signedIn = false
            graph.signedIn = false
            coordinator.serviceStarted()
            coordinator.reconcile()
            gmail.signedIn = true
            coordinator.recovered("gmail")
            coordinator.reconcile()
            coordinator.recovered("graph")
            assertEquals(listOf("show gmail Gmail", "clear gmail"), log)
            assertEquals(setOf("gmail"), queried.toSet())
        }

    @Test fun `a tick never raises an alert`() =
        runBlocking {
            configured = listOf(gmail, graph)
            gmail.signedIn = false
            graph.signedIn = false
            coordinator.reconcile()
            assertEquals(emptyList<String>(), log)
        }

    @Test fun `a stale alert from before a restart is cleared on the first tick once the provider is available`() =
        runBlocking {
            showing += id("gmail")
            coordinator.reconcile()
            assertEquals(listOf("clear gmail"), log)
        }

    @Test fun `an auth failure while the provider still reads available is not cleared by a tick, only by a success`() =
        runBlocking {
            configured = listOf(gmail, graph)
            coordinator.authRequired(auth("graph", "Outlook")) // e.g. a second 401 with the refresh token kept
            coordinator.reconcile()
            assertEquals(listOf("show graph Outlook"), log)
            coordinator.recovered("graph")
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
            coordinator.recovered("graph")
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
        }

    @Test fun `recovery of one provider never clears the other`() =
        runBlocking {
            configured = listOf(gmail, graph)
            coordinator.authRequired(auth("gmail", "Gmail"))
            coordinator.authRequired(auth("graph", "Outlook"))
            coordinator.recovered("graph")
            assertEquals(listOf("show gmail Gmail", "show graph Outlook", "clear graph"), log)
            assertTrue(id("gmail") in showing)
        }

    @Test fun `the router's own failure alerts each signed-out account by name, never as mail`() =
        runBlocking {
            configured = listOf(gmail, graph)
            gmail.signedIn = false
            graph.signedIn = false
            coordinator.authRequired(MailAuthRequiredException("No mail account is connected", "router", "mail"))
            assertEquals(listOf("show gmail Gmail", "show graph Outlook"), log)
            assertFalse(id("router") in showing)
            gmail.signedIn = true
            graph.signedIn = true
            coordinator.recovered("gmail")
            coordinator.recovered("graph")
            assertEquals(listOf("show gmail Gmail", "show graph Outlook", "clear gmail", "clear graph"), log)
            assertEquals(setOf(AlertNotifier.DELIVERY_REVIEW_NOTIFICATION_ID), showing)
        }

    @Test fun `a disconnected provider loses its alert`() =
        runBlocking {
            configured = listOf(gmail, graph)
            coordinator.authRequired(auth("graph", "Outlook"))
            configured = listOf(gmail)
            coordinator.disconnected("graph")
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
            coordinator.reconcile()
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
        }

    @Test fun `a failure from a call still in flight when Outlook was disconnected does not bring the alert back`() =
        runBlocking {
            configured = listOf(gmail, graph)
            coordinator.authRequired(auth("graph", "Outlook"))
            configured = listOf(gmail)
            graph.signedIn = false
            coordinator.disconnected("graph")
            coordinator.authRequired(auth("graph", "Outlook"))
            coordinator.reconcile()
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
            assertFalse(id("graph") in showing)
        }

    @Test fun `an alert this coordinator posted for a provider later removed is cleared by a tick`() =
        runBlocking {
            configured = listOf(gmail, graph)
            coordinator.authRequired(auth("graph", "Outlook"))
            configured = listOf(gmail)
            coordinator.reconcile()
            assertEquals(listOf("show graph Outlook", "clear graph"), log)
        }

    @Test fun `a healthy steady state stops asking the notification service`() =
        runBlocking {
            configured = listOf(gmail, graph)
            repeat(5) { coordinator.reconcile() }
            assertEquals(emptyList<String>(), log)
            assertEquals(listOf("gmail", "graph"), queried) // once each
        }

    @Test fun `a failing alert query is treated as unknown and the action is still taken`() =
        runBlocking {
            val failing =
                AuthAlertCoordinator(
                    providers = { listOf(gmail) },
                    configured = { listOf(gmail) },
                    isShowing = { error("binder failure") },
                    show = { p, _ -> log += "show $p" },
                    clear = { p -> log += "clear $p" },
                )
            failing.authRequired(auth("gmail", "Gmail"))
            failing.recovered("gmail")
            assertEquals(listOf("show gmail", "clear gmail"), log)
        }

    @Test fun `calls from many threads never overlap`() {
        // Gmail signed out with its alert up: every reconcile asks the shade (nothing gets cached).
        gmail.signedIn = false
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val serial =
            AuthAlertCoordinator(
                providers = { listOf(gmail) },
                configured = { listOf(gmail) },
                isShowing = {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    Thread.sleep(2)
                    inside.decrementAndGet()
                    true
                },
                show = { _, _ -> },
                clear = { _ -> },
            )
        runBlocking(Dispatchers.Default) {
            (1..40).map { launch { serial.reconcile() } }.joinAll()
        }
        assertEquals(1, maxInside.get())
    }
}
