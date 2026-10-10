package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.CancellationException

/**
 * A [MailTransport] over several providers, so the app keeps working when one mailbox is down.
 *
 * [members] returns the transports preferred first, signed in or not; the router filters on
 * [MailTransport.isAvailable] on every call, so members can come and go.
 *
 * With one configured member the router is a pass-through (same calls, results and exception
 * objects), so a Gmail-only install behaves as if it talked to Gmail directly.
 *
 * [onAuthRequired] fires only for auth failures the router absorbs by moving on; a rethrown one is
 * the caller's to alert on, which avoids posting the same notification twice.
 */
class MailRouter(
    private val members: () -> List<MailTransport>,
    private val onAuthRequired: suspend (MailAuthRequiredException) -> Unit = {},
    private val onRecovered: suspend (providerId: String) -> Unit = {},
    private val logPossibleDuplicate: suspend (String) -> Unit = {},
) : MailTransport {
    override val providerId: String get() = members().singleOrNull()?.providerId ?: ROUTER_ID
    override val displayName: String get() = members().singleOrNull()?.displayName ?: ROUTER_NAME
    override val isAvailable: Boolean get() = members().any { it.isAvailable }

    private fun usable(): List<MailTransport> = members().filter { it.isAvailable }

    /**
     * The account a send would try first: the first available member, else the first member, else [ROUTER_NAME].
     */
    fun primaryDisplayName(): String {
        val all = members()
        return (all.firstOrNull { it.isAvailable } ?: all.firstOrNull())?.displayName ?: ROUTER_NAME
    }

    override suspend fun accountEmail(): String? = usable().firstOrNull()?.accountEmail()

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        val usable = usable()
        if (usable.isEmpty()) {
            // A lone signed-out member answers for itself, so its own exception and message (and
            // anything else it does when signed out) stay exactly what they are without a router.
            members().singleOrNull()?.let { return it.send(payload, attachmentPaths, deliveryKey, verifyPriorDelivery) }
            throw MailAuthRequiredException("No mail account is connected", providerId, displayName)
        }
        if (usable.size == 1) {
            // Nothing to fail over to: hand the call over unchanged.
            val member = usable.single()
            val receipt = member.send(payload, attachmentPaths, deliveryKey, verifyPriorDelivery)
            safely { onRecovered(member.providerId) }
            return receipt
        }
        val absorbedAuth = mutableListOf<MailAuthRequiredException>()
        if (verifyPriorDelivery) {
            for (member in usable) {
                val found = suspendRunCatching { member.findSent(deliveryKey) }
                found.getOrNull()?.let {
                    reportAbsorbed(absorbedAuth, rethrown = null)
                    return it.copy(reconciled = true)
                }
                // A failed check must not fail the send: that would defeat failover while this
                // provider is down. Go on, and say a duplicate is possible.
                found.exceptionOrNull()?.let { failure ->
                    if (failure is MailAuthRequiredException) {
                        absorbedAuth += failure
                    } else {
                        safely {
                            logPossibleDuplicate(
                                "${member.displayName} could not check its Sent folder before a retry; continuing (possible duplicate)",
                            )
                        }
                    }
                }
            }
        }
        var firstFailure: Throwable? = null
        for ((index, member) in usable.withIndex()) {
            val hasNext = index < usable.lastIndex
            val receipt =
                try {
                    member.send(payload, attachmentPaths, deliveryKey, verifyPriorDelivery = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure
                    when (classify(failure)) {
                        Kind.AUTH -> absorbedAuth += failure as MailAuthRequiredException
                        Kind.DEFINITE -> Unit
                        Kind.AMBIGUOUS -> {
                            val found = suspendRunCatching { member.findSent(deliveryKey) }
                            found.getOrNull()?.let {
                                reportAbsorbed(absorbedAuth, rethrown = null)
                                return it.copy(reconciled = true)
                            }
                            val lookupFailure = found.exceptionOrNull()
                            if (lookupFailure is MailAuthRequiredException) absorbedAuth += lookupFailure
                            // On the last member nothing else is sent, so no duplicate can follow.
                            if (hasNext) {
                                val reason =
                                    if (found.isFailure) {
                                        "${member.displayName} send was ambiguous and could not be verified; trying the next account (possible duplicate)"
                                    } else {
                                        "${member.displayName} send failed ambiguously and was not found in Sent yet; trying the next account (possible duplicate if it was accepted)"
                                    }
                                safely { logPossibleDuplicate(reason) }
                            }
                        }
                    }
                    null
                }
            if (receipt != null) {
                reportAbsorbed(absorbedAuth, rethrown = null)
                safely { onRecovered(member.providerId) }
                return receipt
            }
        }
        // Safe: usable is non-empty and every path that neither returned nor threw recorded a failure.
        val failure = firstFailure!!
        reportAbsorbed(absorbedAuth, rethrown = failure)
        throw failure
    }

    /**
     * The first usable member's receipt for [deliveryKey]. A lone member's failure is rethrown; with
     * several, a failing lookup is skipped (auth failures alerted) and null means no answering member has it.
     */
    override suspend fun findSent(deliveryKey: String): MailReceipt? {
        val usable = usable()
        usable.singleOrNull()?.let { return it.findSent(deliveryKey) }
        val absorbedAuth = mutableListOf<MailAuthRequiredException>()
        var receipt: MailReceipt? = null
        for (member in usable) {
            receipt =
                try {
                    member.findSent(deliveryKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is MailAuthRequiredException) absorbedAuth += failure
                    null
                }
            if (receipt != null) break
        }
        reportAbsorbed(absorbedAuth, rethrown = null)
        return receipt
    }

    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        val results = eachUsable { it.pollReplies(knownMessageIds) }
        return MailPollResult(results.flatMap { it.replies }, results.flatMap { it.fetchFailures })
    }

    override suspend fun findCommands(search: CommandSearch): CommandScan {
        val scans = eachUsable { it.findCommands(search) }
        val interleaved = mutableListOf<MailMessage>()
        val longest = scans.maxOfOrNull { it.candidates.size } ?: 0
        for (index in 0 until longest) {
            scans.forEach { scan -> scan.candidates.getOrNull(index)?.let { interleaved += it } }
        }
        return CommandScan(interleaved, scans.flatMap { it.unreadable })
    }

    override suspend fun fetchContent(message: MailMessage): MailMessage = memberFor(message.id).fetchContent(message)

    override suspend fun markRead(messageId: String) = memberFor(messageId).markRead(messageId)

    override suspend fun checkForBounces(): List<BounceNotice> = eachUsable { it.checkForBounces() }.flatten()

    override fun clearSession() = members().forEach { it.clearSession() }

    private fun memberFor(id: String): MailTransport =
        members().firstOrNull { it.providerId == MailIds.providerOf(id) }
            ?: throw IllegalArgumentException("No mail account for id $id")

    /**
     * Runs [call] on every usable member and returns the successes; rethrows the first failure only if all failed.
     */
    private suspend fun <T> eachUsable(call: suspend (MailTransport) -> T): List<T> {
        val results = mutableListOf<T>()
        var firstFailure: Exception? = null
        val absorbedAuth = mutableListOf<MailAuthRequiredException>()
        val succeeded = mutableListOf<String>()
        for (member in usable()) {
            try {
                results += call(member)
                succeeded += member.providerId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
                if (failure is MailAuthRequiredException) absorbedAuth += failure
            }
        }
        succeeded.forEach { safely { onRecovered(it) } }
        val rethrown = if (results.isEmpty()) firstFailure else null
        reportAbsorbed(absorbedAuth, rethrown)
        if (rethrown != null) throw rethrown
        return results
    }

    /**
     * Alerts once per provider for absorbed auth failures; the rethrown one (and its provider's) is the caller's.
     */
    private suspend fun reportAbsorbed(
        authFailures: List<MailAuthRequiredException>,
        rethrown: Throwable?,
    ) {
        val callerAlerts = (rethrown as? MailAuthRequiredException)?.providerId
        authFailures
            .filter { it !== rethrown && it.providerId != callerAlerts }
            .distinctBy { it.providerId }
            .forEach { safely { onAuthRequired(it) } }
    }

    /** Runs a callback; its failure (other than cancellation) never changes what the router does. */
    private suspend fun safely(callback: suspend () -> Unit) {
        suspendRunCatching { callback() }
    }

    private enum class Kind { AUTH, DEFINITE, AMBIGUOUS }

    private fun classify(failure: Throwable): Kind =
        when (failure) {
            is MailAuthRequiredException -> Kind.AUTH
            is MailHttpException -> if (failure.statusCode in 500..599) Kind.AMBIGUOUS else Kind.DEFINITE
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> Kind.DEFINITE
            is java.io.IOException -> Kind.AMBIGUOUS
            else -> Kind.AMBIGUOUS
        }

    companion object {
        const val ROUTER_ID = "router"
        const val ROUTER_NAME = "mail"
    }
}
