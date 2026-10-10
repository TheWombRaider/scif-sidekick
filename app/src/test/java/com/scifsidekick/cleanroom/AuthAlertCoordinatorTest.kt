package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.service.AuthAlertAction
import com.scifsidekick.cleanroom.service.AuthAlertCoordinator
import com.scifsidekick.cleanroom.service.ProviderAlertState
import com.scifsidekick.cleanroom.service.authAlertActions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private val gmail = FakeMailTransport("gmail", "Gmail")
    private val graph = FakeMailTransport("graph", "Outlook")
    private var configured: List<MailTransport> = listOf(gmail)
    private val showing = mutableSetOf<String>()
    private val log = mutableListOf<String>()
    private var queries = 0

    private val coordinator =
        AuthAlertCoordinator(
            providers = { listOf(gmail, graph) },
            configured = { configured },
            isShowing = {
                queries++
                it in showing
            },
            show = { id, name ->
                log += "show $id $name"
                showing += id
            },
            clear = { id ->
                log += "clear $id"
                showing -= id
            },
        )

    private fun auth(
        id: String,
        name: String,
    ) = MailAuthRequiredException("reconnect", id, name)

    @Test fun `an auth failure shows once however often it is reported`() =
        runBlocking {
            coordinator.authRequired(auth("gmail", "Gmail"))
            coordinator.authRequired(auth("gmail", "Gmail"))
            assertEquals(listOf("show gmail Gmail"), log)
        }

    @Test fun `an auth failure shows again after the user dismissed the alert`() =
        runBlocking {
            coordinator.authRequired(auth("gmail", "Gmail"))
            showing.clear() // swiped away
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

    @Test fun `gmail only service start alerts exactly gmail`() =
        runBlocking {
            gmail.signedIn = false
            graph.signedIn = false
            coordinator.serviceStarted()
            assertEquals(listOf("show gmail Gmail"), log)
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
            showing += "gmail"
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

    @Test fun `an alert left over after the provider was removed is cleared by a tick`() =
        runBlocking {
            showing += "graph"
            coordinator.reconcile()
            assertEquals(listOf("clear graph"), log)
        }

    @Test fun `a healthy steady state stops asking the notification service`() =
        runBlocking {
            repeat(5) { coordinator.reconcile() }
            assertEquals(emptyList<String>(), log)
            assertEquals(2, queries) // gmail and graph, once each
        }

    @Test fun `a failing alert query is treated as unknown and the action is still taken`() =
        runBlocking {
            val failing =
                AuthAlertCoordinator(
                    providers = { listOf(gmail) },
                    configured = { listOf(gmail) },
                    isShowing = { error("binder failure") },
                    show = { id, _ -> log += "show $id" },
                    clear = { id -> log += "clear $id" },
                )
            failing.authRequired(auth("gmail", "Gmail"))
            failing.recovered("gmail")
            assertEquals(listOf("show gmail", "clear gmail"), log)
        }

    @Test fun `concurrent reports are serialized`() =
        runBlocking {
            configured = listOf(gmail, graph)
            (1..50)
                .map { i -> async { if (i % 2 == 0) coordinator.authRequired(auth("graph", "Outlook")) else coordinator.reconcile() } }
                .awaitAll()
            assertEquals(listOf("show graph Outlook"), log)
        }
}
