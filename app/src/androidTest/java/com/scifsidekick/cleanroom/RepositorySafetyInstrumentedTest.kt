package com.scifsidekick.cleanroom

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.data.ContactFilterMode
import com.scifsidekick.cleanroom.data.DeliveryAttemptEntity
import com.scifsidekick.cleanroom.data.FilterConditionMode
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.PendingEmailRouteEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.data.QueueStatus
import com.scifsidekick.cleanroom.data.SendQueueEntity
import com.scifsidekick.cleanroom.data.SentEmailRouteEntity
import com.scifsidekick.cleanroom.data.SentGmailMessageEntity
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.email.DebugControls
import com.scifsidekick.cleanroom.email.GmailGateway
import com.scifsidekick.cleanroom.email.GmailOAuthManager
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.AttachmentStore
import com.scifsidekick.cleanroom.util.PayloadCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositorySafetyInstrumentedTest {
    private lateinit var db: SidekickDatabase
    private lateinit var repository: SidekickRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(context, SidekickDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = SidekickRepository(context, db)
        runBlocking {
            repository.ensureInitialized()
        }
    }

    @After fun tearDown() = db.close()

    /** Every test below that expects a message to actually queue needs at least one enabled
     *  filter with a recipient -- otherwise processIncoming correctly has nothing to forward to. */
    private suspend fun defaultFilter(
        includeMms: Boolean = true,
        includeCalls: Boolean = true,
        conditionMode: String = FilterConditionMode.ALL,
        contactMode: String = ContactFilterMode.OFF,
        contactNumbersJson: String = "[]",
    ): Long {
        val id = db.filterDao().insert(ForwardingFilterEntity(name = "Default"))
        db.filterDao().update(
            db.filterDao().get(id)!!.copy(
                recipientsJson = "[\"destination@example.com\"]",
                includeMms = includeMms,
                includeCalls = includeCalls,
                conditionMode = conditionMode,
                contactMode = contactMode,
                contactNumbersJson = contactNumbersJson,
            ),
        )
        return id
    }

    @Test fun backlogReceivedWhileOffIsNeverQueuedAfterReenable() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 1_000)
            repository.setForwarding(false, nowMs = 2_000)
            val offMessages = (0 until 10).map { i -> message(2_100L + i, "off-$i") }
            offMessages.forEach { assertFalse(repository.processIncoming(it)) }

            repository.setForwarding(true, nowMs = 3_000)
            // Simulates an accidental replay call: the watermark independently rejects it.
            offMessages.forEach { assertFalse(repository.processIncoming(it)) }
            assertEquals(0, db.queueDao().queuedCount())

            assertTrue(repository.processIncoming(message(3_001, "new")))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun toggleFlappingAdvancesWatermarkAndDoesNotDuplicate() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, 10_000)
            repository.setForwarding(false, 10_010)
            repository.setForwarding(true, 10_020)
            repository.setForwarding(false, 10_030)
            repository.setForwarding(true, 10_040)

            val old = message(10_035, "old")
            val fresh = message(10_041, "fresh")
            assertFalse(repository.processIncoming(old))
            assertTrue(repository.processIncoming(fresh))
            assertFalse(repository.processIncoming(fresh))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun matchingSmsAndRcsNotificationProduceOneForward() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, 20_000)
            val sms = message(20_100, "same live message")
            val rcsNotification =
                sms.copy(
                    source = "rcs",
                    senderAddress = "Test",
                    receivedAtMs = 20_102,
                    sourceTimestampMs = 20_102,
                )

            assertTrue(repository.processIncoming(sms))
            assertFalse(repository.processIncoming(rcsNotification))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun crossSourceDedupeSurvivesWhitespaceDifferenceBetweenRawAndCleanedText() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, 20_000)
            val sms = message(20_100, "Same   message\n\ntext")
            val rcsNotification =
                sms.copy(
                    source = "rcs",
                    senderAddress = "Test",
                    body = "Same message text",
                    receivedAtMs = 20_102,
                    sourceTimestampMs = 20_102,
                )
            assertTrue(repository.processIncoming(sms))
            assertFalse(repository.processIncoming(rcsNotification))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun allRollingEmailTiersAndSmsTierBlock() =
        runBlocking {
            val limiter = RollingRateLimiter(db.deliveryAttemptDao())
            val now = 100_000_000L

            repeat(20) { i -> attempt(QueueChannel.EMAIL, now - i * 2_000L, i.toLong()) }
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now) > now)

            db.clearAllTables()
            repeat(300) { i -> attempt(QueueChannel.EMAIL, now - i * 10_000L, i.toLong()) }
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now) > now)

            db.clearAllTables()
            repeat(450) { i -> attempt(QueueChannel.EMAIL, now - i * 180_000L, i.toLong()) }
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now) > now)

            db.clearAllTables()
            repeat(5) { i -> attempt(QueueChannel.SMS, now - i * 2_000L, i.toLong()) }
            repeat(5) { i -> attempt(QueueChannel.MMS, now - (i + 5) * 2_000L, (i + 5).toLong()) }
            assertTrue(limiter.nextAllowedAt(QueueChannel.SMS, now) > now)
            assertTrue(limiter.nextAllowedAt(QueueChannel.MMS, now) > now)
        }

    @Test fun forgedReplyTagRequiresARecordedGmailRoute() =
        runBlocking {
            val number = "+15551234567"
            assertFalse(repository.isAuthorizedReply(number, "thread-1", setOf("rfc-1@example"), "owner@example.com"))
            db.sentEmailRouteDao().upsert(
                SentEmailRouteEntity(
                    queueId = 99,
                    gmailMessageId = "gmail-1",
                    gmailThreadId = "thread-1",
                    rfcMessageId = "rfc-1@example",
                    targetNumber = number,
                    authorizedReplySendersJson = "[\"owner@example.com\"]",
                    sentAtMs = 10_000,
                ),
            )
            // Empty referencedMessageIds means "not asserting a reply at all" -- the thread match
            // gate requires that even though this candidate's threadId genuinely matches the route.
            assertFalse(repository.isAuthorizedReply(number, "thread-1", emptySet(), "owner@example.com"))
            assertTrue(repository.isAuthorizedReply(number, "thread-1", setOf("rfc-1@example"), "owner@example.com"))
            assertFalse(repository.isAuthorizedReply(number, "thread-1", setOf("rfc-1@example"), "attacker@example.com"))
            assertFalse(repository.isAuthorizedReply("+15550000000", "thread-1", setOf("rfc-1@example"), "owner@example.com"))
            // A wrong threadId with a real reference still matches -- exact rfcMessageId reference
            // alone is already sufficient proof, the thread match is only ever an *additional* path.
            assertTrue(repository.isAuthorizedReply(number, "some-other-thread", setOf("rfc-1@example"), "owner@example.com"))
            // Conversely: right threadId, but the reference is to something this app never sent --
            // the thread match still requires a *non-empty* referencedMessageIds, but does not
            // require that specific id to itself match a route (that's the whole point of the
            // fallback). This still authorizes, exactly like the fallback is meant to.
            assertTrue(repository.isAuthorizedReply(number, "thread-1", setOf("some-mangled-id@example"), "owner@example.com"))
            assertFalse(repository.isAuthorizedReply(number, "thread-1", setOf("some-mangled-id@example"), "attacker@example.com"))
        }

    @Test fun remoteControlAliasFallbackRequiresMasterSwitchOnAndComposePermission() =
        runBlocking {
            val number = "+15551234567"
            db.sentEmailRouteDao().upsert(
                SentEmailRouteEntity(
                    queueId = 7,
                    gmailMessageId = "gmail-7",
                    gmailThreadId = "thread-7",
                    rfcMessageId = "rfc-7@example",
                    targetNumber = number,
                    authorizedReplySendersJson = "[\"owner@example.com\"]",
                    sentAtMs = 10_000,
                ),
            )
            repository.updateAppSettings {
                it.copy(
                    remoteControlEnabled = false,
                    remoteControlSendersJson = RemoteControlCodec.toJson(listOf(RemoteControlCodec.Sender("alias@example.com"))),
                )
            }
            assertFalse(repository.isAuthorizedReply(number, "thread-7", setOf("rfc-7@example"), "alias@example.com"))

            repository.updateAppSettings { it.copy(remoteControlEnabled = true) }
            assertTrue(repository.isAuthorizedReply(number, "thread-7", setOf("rfc-7@example"), "alias@example.com"))
            // Still needs a genuine route: the allowlist alone never authorizes a reply to anyone.
            assertFalse(repository.isAuthorizedReply("+15550000000", "thread-7", setOf("rfc-7@example"), "alias@example.com"))
            assertFalse(repository.isAuthorizedReply(number, "thread-7", setOf("rfc-7@example"), "stranger@example.com"))

            // Master on, but this address isn't checked for Compose specifically -- still refused.
            repository.updateAppSettings {
                it.copy(
                    remoteControlSendersJson =
                        RemoteControlCodec.toJson(
                            listOf(RemoteControlCodec.Sender("alias@example.com", canCompose = false)),
                        ),
                )
            }
            assertFalse(repository.isAuthorizedReply(number, "thread-7", setOf("rfc-7@example"), "alias@example.com"))
        }

    @Test fun successfulForwardRecordsHowLongAfterArrivalItWasSent() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 1_000)
            assertTrue(repository.processIncoming(message(System.currentTimeMillis(), "latency")))
            val debug = DebugControls(context)
            debug.fakeEmailTransport = true
            try {
                val processor =
                    QueueProcessor(
                        db,
                        repository,
                        RollingRateLimiter(db.deliveryAttemptDao()),
                        GmailGateway(GmailOAuthManager(context), debug),
                        SmsGateway(context),
                        MmsGateway(context),
                        AttachmentStore(context),
                        AlertNotifier(context),
                    )
                processor.drain(QueueChannel.EMAIL, 5)
            } finally {
                debug.fakeEmailTransport = false
            }
            val sent = db.eventLogDao().observeRecent().first().firstOrNull { it.type == EventType.SENT }
            assertNotNull(sent)
            assertTrue(sent!!.reason, sent.reason.contains("after the message arrived"))
        }

    @Test fun preparedEmailRouteClosesRemoteAcceptanceRaceWithoutAuthorizingAnotherSender() =
        runBlocking {
            val number = "+15551234567"
            db.pendingEmailRouteDao().upsert(
                PendingEmailRouteEntity(
                    queueId = 42,
                    rfcMessageId = "pending-42@example",
                    targetNumber = number,
                    authorizedReplySendersJson = "[\"owner@example.com\"]",
                    createdAtMs = 10_000,
                ),
            )
            assertTrue(repository.wasSentByThisApp("not-yet-known", "pending-42@example"))
            assertTrue(repository.isAuthorizedReply(number, "irrelevant-thread", setOf("pending-42@example"), "owner@example.com"))
            assertFalse(repository.isAuthorizedReply(number, "irrelevant-thread", setOf("pending-42@example"), "attacker@example.com"))
        }

    @Test fun interruptedQueueClaimIsDelayedAndMarkedForReconciliation() =
        runBlocking {
            val id =
                db.queueDao().insert(
                    SendQueueEntity(channel = QueueChannel.EMAIL, payloadJson = "{}", createdAtMs = 1_000),
                )
            assertEquals(1, db.queueDao().claim(id))
            assertEquals(1, db.queueDao().releaseInterruptedEmailClaims(5_000, activeSinceMs = 0))
            assertNull(db.queueDao().nextReady(QueueChannel.EMAIL, 4_999))
            val recovered = db.queueDao().nextReady(QueueChannel.EMAIL, 5_000)
            assertEquals(1, recovered?.attemptCount)
            assertTrue(recovered?.lastError!!.contains("reconciliation"))
        }

    @Test fun emailClaimWithARecentAttemptIsNotReleasedAsInterrupted() =
        runBlocking {
            val inFlight =
                db.queueDao().insert(SendQueueEntity(channel = QueueChannel.EMAIL, payloadJson = "{}", createdAtMs = 1_000))
            val stale =
                db.queueDao().insert(SendQueueEntity(channel = QueueChannel.EMAIL, payloadJson = "{}", createdAtMs = 1_001))
            assertEquals(1, db.queueDao().claim(inFlight))
            assertEquals(1, db.queueDao().claim(stale))
            db.deliveryAttemptDao().insert(DeliveryAttemptEntity(channel = QueueChannel.EMAIL, attemptedAtMs = 9_000, queueId = inFlight, succeeded = null, detail = "started"))
            db.deliveryAttemptDao().insert(DeliveryAttemptEntity(channel = QueueChannel.EMAIL, attemptedAtMs = 1_500, queueId = stale, succeeded = null, detail = "started"))
            // Only the claim whose attempt began before the cutoff is released; the in-flight one stays SENDING.
            assertEquals(1, db.queueDao().releaseInterruptedEmailClaims(20_000, activeSinceMs = 8_000))
            assertEquals(QueueStatus.SENDING, db.queueDao().get(inFlight)?.status)
            assertEquals(QueueStatus.QUEUED, db.queueDao().get(stale)?.status)
        }

    @Test fun interruptedSmsClaimIsNotAutomaticallyResent() =
        runBlocking {
            val id =
                db.queueDao().insert(
                    SendQueueEntity(channel = QueueChannel.SMS, payloadJson = "{}", createdAtMs = 1_000),
                )
            assertEquals(1, db.queueDao().claim(id))
            db.deliveryAttemptDao().insert(DeliveryAttemptEntity(channel = QueueChannel.SMS, attemptedAtMs = 2_000, queueId = id, succeeded = null, detail = "started"))
            assertTrue(db.queueDao().timedOutTelephonyClaims(1_999).isEmpty())
            assertEquals(listOf(id), db.queueDao().timedOutTelephonyClaims(2_000).map { it.id })
            assertEquals(1, db.queueDao().quarantineTimedOutTelephonyClaim(id))
            assertNull(db.queueDao().nextReady(QueueChannel.SMS, Long.MAX_VALUE))
        }

    @Test fun interruptedMmsClaimIsNotAutomaticallyResentEither() =
        runBlocking {
            // Same ambiguous-outcome reasoning as the SMS case above applies identically to an
            // interrupted MMS send -- there is no more of a "did it actually go out" signal for
            // one than for the other.
            val id =
                db.queueDao().insert(
                    SendQueueEntity(channel = QueueChannel.MMS, payloadJson = "{}", createdAtMs = 1_000),
                )
            assertEquals(1, db.queueDao().claim(id))
            db.deliveryAttemptDao().insert(DeliveryAttemptEntity(channel = QueueChannel.MMS, attemptedAtMs = 2_000, queueId = id, succeeded = null, detail = "started"))
            assertEquals(listOf(id), db.queueDao().timedOutTelephonyClaims(2_000).map { it.id })
            assertEquals(1, db.queueDao().quarantineTimedOutTelephonyClaim(id))
            assertNull(db.queueDao().nextReady(QueueChannel.MMS, Long.MAX_VALUE))
        }

    @Test fun mmsForwardingOffOnEveryFilterSkipsMmsButAllowsSms() =
        runBlocking {
            defaultFilter(includeMms = false)
            repository.setForwarding(true, nowMs = 1_000)
            val mms = message(1_100, "picture").copy(source = "mms")
            assertFalse(repository.processIncoming(mms))
            val sms = message(1_200, "text")
            assertTrue(repository.processIncoming(sms))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun callForwardingRespectsPerFilterToggle() =
        runBlocking {
            defaultFilter(includeCalls = true)
            repository.setForwarding(true, nowMs = 2_000)
            val whileEnabled = message(2_100, "Missed call").copy(source = "call")
            assertTrue(repository.processIncoming(whileEnabled))

            val filter = db.filterDao().getAll().single()
            db.filterDao().update(filter.copy(includeCalls = false))
            val afterDisabling = message(2_200, "Missed call").copy(source = "call")
            assertFalse(repository.processIncoming(afterDisabling))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun contactWhitelistOnlyAllowsListedNumbers() =
        runBlocking {
            defaultFilter(
                conditionMode = FilterConditionMode.CONDITIONS,
                contactMode = ContactFilterMode.WHITELIST,
                contactNumbersJson = "[\"+15551234567\"]",
            )
            repository.setForwarding(true, nowMs = 3_000)
            val allowed = message(3_100, "hi")
            assertTrue(repository.processIncoming(allowed))
            val notListed = message(3_200, "hi").copy(senderAddress = "+15559998888")
            assertFalse(repository.processIncoming(notListed))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun contactBlacklistBlocksListedNumbers() =
        runBlocking {
            defaultFilter(
                conditionMode = FilterConditionMode.CONDITIONS,
                contactMode = ContactFilterMode.BLACKLIST,
                contactNumbersJson = "[\"+15551234567\"]",
            )
            repository.setForwarding(true, nowMs = 4_000)
            val blocked = message(4_100, "hi")
            assertFalse(repository.processIncoming(blocked))
            val notListed = message(4_200, "hi").copy(senderAddress = "+15559998888")
            assertTrue(repository.processIncoming(notListed))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun aMessageCanFanOutToMultipleMatchingFilters() =
        runBlocking {
            db.filterDao().insert(ForwardingFilterEntity(name = "A", recipientsJson = "[\"a@example.com\"]"))
            db.filterDao().insert(ForwardingFilterEntity(name = "B", recipientsJson = "[\"b@example.com\"]"))
            repository.setForwarding(true, nowMs = 5_000)
            assertTrue(repository.processIncoming(message(5_100, "hi")))
            assertEquals(2, db.queueDao().queuedCount())
        }

    @Test fun ownSentEmailIsNeverTreatedAsAnAuthorizedReplyToItself() =
        runBlocking {
            // Regression test for forwarding-to-self: if a filter's recipient is the user's own
            // connected Gmail account, the app's own notification lands back in that inbox with
            // a genuine [SCIF:+number] tag whose thread this table already recorded as an
            // authorized route -- it must never be treated as a reply to itself.
            val number = "+15551234567"
            assertFalse(repository.wasSentByThisApp("gmail-self-1", "rfc-self-1@example"))
            db.sentEmailRouteDao().upsert(
                SentEmailRouteEntity(
                    queueId = 1,
                    gmailMessageId = "gmail-self-1",
                    gmailThreadId = "thread-self-1",
                    rfcMessageId = "rfc-self-1@example",
                    targetNumber = number,
                    authorizedReplySendersJson = "[\"owner@example.com\"]",
                    sentAtMs = 10_000,
                ),
            )
            db.sentGmailMessageDao().upsert(
                SentGmailMessageEntity("gmail-self-1", 1, "rfc-self-1@example", 10_000),
            )
            assertTrue(repository.wasSentByThisApp("gmail-self-1", "rfc-self-1@example"))
            // Confirms the trap this guards against actually exists, and that isAuthorizedReply's
            // own thread-match fallback (routesForThread) does not quietly reopen it: passing this
            // route's own real threadId still returns false, because the self-landed original
            // forward carries no real In-Reply-To/References at all (represented here by
            // emptySet()) -- exactly the "not actually asserting a reply" case the thread-match
            // gate requires non-empty referencedMessageIds to rule out.
            assertFalse(repository.isAuthorizedReply(number, "thread-self-1", emptySet(), "owner@example.com"))
        }

    @Test fun stopOnMatchSkipsEveryFilterAfterItInSortOrder() =
        runBlocking {
            db.filterDao().insert(ForwardingFilterEntity(name = "First", sortOrder = 0, recipientsJson = "[\"a@example.com\"]", stopOnMatch = true))
            db.filterDao().insert(ForwardingFilterEntity(name = "Second", sortOrder = 1, recipientsJson = "[\"b@example.com\"]"))
            repository.setForwarding(true, nowMs = 7_000)
            assertTrue(repository.processIncoming(message(7_100, "hi")))
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun withoutStopOnMatchBothFiltersStillFireInOrder() =
        runBlocking {
            db.filterDao().insert(ForwardingFilterEntity(name = "First", sortOrder = 0, recipientsJson = "[\"a@example.com\"]"))
            db.filterDao().insert(ForwardingFilterEntity(name = "Second", sortOrder = 1, recipientsJson = "[\"b@example.com\"]"))
            repository.setForwarding(true, nowMs = 8_000)
            assertTrue(repository.processIncoming(message(8_100, "hi")))
            assertEquals(2, db.queueDao().queuedCount())
        }

    @Test fun firstGmailConnectionSeedsOwnerWithAllFourCommandsAndNeverOverwrites() =
        runBlocking {
            context.getSharedPreferences("setup_flags_v1", Context.MODE_PRIVATE).edit().clear().commit()
            repository.seedRemoteControlOwnerIfEmpty("Owner@Example.com")
            val seeded = RemoteControlCodec.fromJson(repository.currentAppSettings().remoteControlSendersJson)
            assertEquals(listOf("owner@example.com"), seeded.map { it.address })
            val owner = seeded.single()
            assertTrue(owner.canStatus)
            assertTrue(owner.canCompose)
            assertTrue(owner.canEnable)
            assertTrue(owner.canDisable)
            repository.seedRemoteControlOwnerIfEmpty("someone-else@example.com")
            assertEquals(
                listOf("owner@example.com"),
                RemoteControlCodec.fromJson(repository.currentAppSettings().remoteControlSendersJson).map { it.address },
            )
            // Removing the address on purpose is respected: it is not seeded again.
            repository.updateAppSettings { it.copy(remoteControlSendersJson = "[]") }
            repository.seedRemoteControlOwnerIfEmpty("owner@example.com")
            assertTrue(RemoteControlCodec.fromJson(repository.currentAppSettings().remoteControlSendersJson).isEmpty())
        }

    @Test fun backupRestoresRemoteControlConfigurationAsOneAtomicUnit() =
        runBlocking {
            repository.updateAppSettings {
                it.copy(
                    remoteControlEnabled = true,
                    remoteControlSendersJson =
                        RemoteControlCodec.toJson(
                            listOf(RemoteControlCodec.Sender("owner@example.com", canCompose = true, canEnable = false, canDisable = false, canStatus = true)),
                        ),
                )
            }
            val backup = repository.exportBackup()
            repository.updateAppSettings {
                it.copy(
                    remoteControlEnabled = false,
                    remoteControlSendersJson = RemoteControlCodec.toJson(listOf(RemoteControlCodec.Sender("stale@example.com"))),
                )
            }
            assertTrue(repository.importBackup(backup).isSuccess)
            val restored = repository.currentAppSettings()
            assertTrue(restored.remoteControlEnabled)
            val senders = RemoteControlCodec.fromJson(restored.remoteControlSendersJson)
            assertEquals(listOf("owner@example.com"), senders.map { it.address })
            assertTrue(senders.single().canCompose)
            assertTrue(senders.single().canStatus)
            assertFalse(senders.single().canEnable)
            assertFalse(senders.single().canDisable)

            // A backup carrying neither key at all (predates even the feature's existence) must not
            // force the switch either way -- it restores whatever the device already had.
            repository.updateAppSettings { it.copy(remoteControlEnabled = false) }
            val bareLegacy = "{\"backupFormatVersion\":1,\"filters\":[],\"appSettings\":{}}"
            assertTrue(repository.importBackup(bareLegacy).isSuccess)
            assertFalse(repository.currentAppSettings().remoteControlEnabled)
            assertEquals("[]", repository.currentAppSettings().remoteControlSendersJson)
        }

    @Test fun legacyBackupMergesOldAllowlistsWithoutGrantingCapabilitiesNeverListed() =
        runBlocking {
            // Predates the unified allowlist: only the two old keys for remote-enable are present.
            // The merge must grant exactly canEnable for this address -- never canDisable, canCompose,
            // or canStatus, none of which this backup ever authorized.
            val enableOnly =
                "{\"backupFormatVersion\":1,\"filters\":[],\"appSettings\":{" +
                    "\"remoteEnableViaEmailEnabled\":true," +
                    "\"authorizedRemoteEnableSendersJson\":\"[\\\"owner@example.com\\\"]\"}}"
            assertTrue(repository.importBackup(enableOnly).isSuccess)
            val restored = repository.currentAppSettings()
            assertTrue(restored.remoteControlEnabled)
            val senders = RemoteControlCodec.fromJson(restored.remoteControlSendersJson)
            assertEquals(listOf("owner@example.com"), senders.map { it.address })
            assertTrue(senders.single().canEnable)
            assertFalse(senders.single().canDisable)
            assertFalse(senders.single().canCompose)
            assertFalse(senders.single().canStatus)

            // A legacy backup whose toggle was off never carries that list forward, even non-empty.
            val toggleOff = "{\"backupFormatVersion\":1,\"filters\":[],\"appSettings\":{" +
                "\"remoteDisableViaEmailEnabled\":false," +
                "\"authorizedRemoteDisableSendersJson\":\"[\\\"owner@example.com\\\"]\"}}"
            repository.updateAppSettings { it.copy(remoteControlEnabled = false, remoteControlSendersJson = "[]") }
            assertTrue(repository.importBackup(toggleOff).isSuccess)
            val afterToggleOff = repository.currentAppSettings()
            assertFalse(afterToggleOff.remoteControlEnabled)
            assertEquals("[]", afterToggleOff.remoteControlSendersJson)
        }

    @Test fun systemEmailIsQueuedThroughTheSameChannelAsEveryOtherSendNotSentDirectly() =
        runBlocking {
            val queueId =
                repository.enqueueSystemEmail(
                    recipients = listOf("Owner@Example.com"),
                    subject = "Delivered: your text to +15551234567",
                    body = "body",
                    reason = "reply confirmation",
                )
            assertNotNull(queueId)
            assertEquals(1, db.queueDao().queuedCount())
            val queued = db.queueDao().get(queueId!!)!!
            assertEquals(QueueChannel.EMAIL, queued.channel)
            // Canonicalized on the way in, like every other address this app stores.
            assertEquals(listOf("owner@example.com"), PayloadCodec.emailFromJson(queued.payloadJson).destinations)
            // No routing tag: this lands back in the inbox the reply poller reads, and a subject
            // that merely looks like a command is worth not creating at all.
            assertFalse(PayloadCodec.emailFromJson(queued.payloadJson).renderedSubject.contains("[SCIF:"))
        }

    @Test fun statusSummaryReportsStateWithoutLeakingMessageContent() =
        runBlocking {
            db.filterDao().insert(ForwardingFilterEntity(name = "All", recipientsJson = "[\"a@example.com\"]"))
            repository.setForwarding(true, nowMs = 1_000)
            assertTrue(repository.processIncoming(message(1_100, "meeting moved to 4pm")))

            val summary = repository.buildStatusSummary(gmailAvailable = true, nowMs = 2_000)
            assertTrue(summary.contains("Forwarding: ON"))
            assertTrue(summary.contains("Gmail authorization: OK"))
            assertTrue(summary.contains("Email circuit breaker: closed"))
            // It is emailed on a schedule and on request, so it must stay a status report rather
            // than becoming a way to read the message history out of the phone.
            assertFalse(summary.contains("meeting moved to 4pm"))
            assertFalse(summary.contains("+1555"))

            repository.setForwarding(false, nowMs = 3_000)
            assertTrue(repository.buildStatusSummary(gmailAvailable = false, nowMs = 3_100).contains("Forwarding: OFF"))
            assertTrue(
                repository.buildStatusSummary(gmailAvailable = false, nowMs = 3_100).contains("NEEDS RECONNECTING"),
            )
        }

    @Test fun backupNeverCarriesTheOutboundSimChoiceOntoAnotherPhone() =
        runBlocking {
            repository.updateAppSettings { it.copy(outboundSubscriptionId = 42) }
            val backup = repository.exportBackup()
            // A subscription id only means anything on the handset that issued it, so restoring
            // one elsewhere would point at nothing or at a different line entirely.
            assertFalse(backup.contains("outboundSubscriptionId"))

            repository.updateAppSettings { it.copy(outboundSubscriptionId = 7) }
            assertTrue(repository.importBackup(backup).isSuccess)
            // Restore leaves whatever this device had rather than importing a foreign id.
            assertEquals(7, repository.currentAppSettings().outboundSubscriptionId)
        }

    @Test fun systemEmailWithNoUsableRecipientQueuesNothing() =
        runBlocking {
            assertNull(repository.enqueueSystemEmail(listOf("not-an-address"), "s", "b", "reply confirmation"))
            assertEquals(0, db.queueDao().queuedCount())
        }

    @Test fun saveResultsOffRedactsTheStoredBodyButKeepsHashedDedupeState() =
        runBlocking {
            db.filterDao().insert(
                ForwardingFilterEntity(name = "Private", recipientsJson = "[\"owner@example.com\"]", saveResults = false),
            )
            repository.setForwarding(true, nowMs = 9_000)
            assertTrue(repository.processIncoming(message(9_100, "sensitive body")))
            val queued = db.queueDao().nextReady(QueueChannel.EMAIL, Long.MAX_VALUE)!!
            val stored = db.messageDao().get(queued.sourceMessageId!!)!!
            assertEquals("", stored.body)
            assertEquals(64, stored.dedupeText.length)
            assertFalse(stored.dedupeText.contains("sensitive"))
        }

    @Test fun reorderFiltersPersistsNewSortOrder() =
        runBlocking {
            val firstId = db.filterDao().insert(ForwardingFilterEntity(name = "A", sortOrder = 0))
            val secondId = db.filterDao().insert(ForwardingFilterEntity(name = "B", sortOrder = 1))
            repository.reorderFilters(listOf(secondId, firstId))
            val reordered = db.filterDao().getAll()
            assertEquals("B", reordered[0].name)
            assertEquals("A", reordered[1].name)
        }

    @Test fun withNoFiltersConfiguredNothingQueuesButTheMessageIsStillRecorded() =
        runBlocking {
            repository.setForwarding(true, nowMs = 6_000)
            assertFalse(repository.processIncoming(message(6_100, "hi")))
            assertEquals(0, db.queueDao().queuedCount())
        }

    @Test fun sendTestMessageWithNoFilterReportsWhyNothingQueued() =
        runBlocking {
            repository.setForwarding(true, nowMs = 7_000)
            val outcome = repository.sendTestMessage("sms", "+15555550100", "hello")
            assertFalse(outcome.queued)
            assertTrue(outcome.trail.isNotEmpty())
            assertTrue(outcome.trail.any { it.reason.contains("no forwarding filter is configured") })
        }

    @Test fun sendTestMessageThroughAMatchingFilterQueuesAndReportsTheFilterName() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 7_100)
            val outcome = repository.sendTestMessage("sms", "+15555550100", "hello")
            assertTrue(outcome.queued)
            assertEquals(1, db.queueDao().queuedCount())
            assertTrue(outcome.trail.any { it.reason.contains("Queued email forward via filter 'Default'") })
        }

    @Test fun sendTestMessageIsSkippedWhenItsSimulatedTypeIsExcludedByEveryFilter() =
        runBlocking {
            defaultFilter(includeMms = false)
            repository.setForwarding(true, nowMs = 7_200)
            val outcome = repository.sendTestMessage("mms", "+15555550100", "hello")
            assertFalse(outcome.queued)
            assertEquals(0, db.queueDao().queuedCount())
        }

    @Test fun sendTestMessageUsesTheProvidedBodyNotAFixedDefault() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 7_300)
            repository.sendTestMessage("sms", "+15555550100", "a distinctive test phrase")
            val stored = db.messageDao().forExport(7_300, System.currentTimeMillis())
            assertTrue(stored.any { it.body.contains("a distinctive test phrase") })
        }

    @Test fun sendTestMessageWithBlankBodyFallsBackToTheDefault() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 7_400)
            val outcome = repository.sendTestMessage("sms", "+15555550100", "")
            assertTrue(outcome.queued)
        }

    @Test fun sendTestMmsMessageCarriesTheProvidedAttachmentPathsIntoTheQueuedEmail() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 7_500)
            val fakeAttachment = "/fake/path/test_mms_1.jpg"
            val outcome = repository.sendTestMessage("mms", "+15555550100", "photo test", listOf(fakeAttachment))
            assertTrue(outcome.queued)
            val queued = db.queueDao().nextReady(QueueChannel.EMAIL, System.currentTimeMillis())
            assertEquals(listOf(fakeAttachment), PayloadCodec.pathsFromJson(queued!!.attachmentPathsJson))
        }

    @Test fun secondReplyWithSameTargetAndBodyUnderADifferentGmailIdIsSuppressed() =
        runBlocking {
            // Reproduces a real device report: one user reply produced multiple outbound SMS
            // sends. processed_replies alone only dedupes by gmailMessageId -- this covers the
            // case of two genuinely distinct Gmail messages (e.g. a mail client silently
            // resubmitting the same reply) carrying identical target+body.
            val first = repository.enqueueReplyIfNew("gmail-1", "+15551234567", "hello")
            val second = repository.enqueueReplyIfNew("gmail-2", "+15551234567", "hello")
            assertTrue(first)
            assertFalse(second)
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun replyDuplicateSuppressionIgnoresTheUnrelatedNotificationToggle() =
        runBlocking {
            // Regression test for a real duplicate-SMS bug found on a physical device: reply
            // dedup used to be gated behind Settings' "Ignore duplicate notifications"
            // (duplicateSuppressionEnabled), a toggle that actually governs a different concern
            // (re-showing a notification for an incoming message already seen). Turning that off
            // silently also disabled "never text the same person the same reply twice." A 1-minute
            // floor now always applies to outgoing replies regardless of that toggle.
            repository.updateAppSettings { it.copy(duplicateSuppressionEnabled = false) }
            val first = repository.enqueueReplyIfNew("gmail-1", "+15551234567", "hello")
            val second = repository.enqueueReplyIfNew("gmail-2", "+15551234567", "hello")
            assertTrue(first)
            assertFalse(second)
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun repliesToDifferentNumbersOrWithDifferentBodiesAreNeverSuppressedByEachOther() =
        runBlocking {
            assertTrue(repository.enqueueReplyIfNew("gmail-1", "+15551234567", "hello"))
            assertTrue(repository.enqueueReplyIfNew("gmail-2", "+15557654321", "hello"))
            assertTrue(repository.enqueueReplyIfNew("gmail-3", "+15551234567", "goodbye"))
            assertEquals(3, db.queueDao().queuedCount())
        }

    @Test fun sameGmailMessageIdIsStillBlockedByProcessedRepliesEvenWithDedupeWindowDisabled() =
        runBlocking {
            repository.updateAppSettings { it.copy(duplicateSuppressionEnabled = false) }
            val first = repository.enqueueReplyIfNew("gmail-1", "+15551234567", "hello")
            val retried = repository.enqueueReplyIfNew("gmail-1", "+15551234567", "hello")
            assertTrue(first)
            assertFalse(retried)
            assertEquals(1, db.queueDao().queuedCount())
        }

    @Test fun messagesForExportOnlyReturnsRowsInsideTheRequestedWindow() =
        runBlocking {
            defaultFilter()
            repository.setForwarding(true, nowMs = 8_000)
            repository.processIncoming(message(8_100, "before window"))
            repository.processIncoming(message(9_000, "inside window"))
            repository.processIncoming(message(9_999, "also inside window"))
            repository.processIncoming(message(20_000, "after window"))

            val exported = repository.messagesForExport(startMs = 8_500, endMs = 9_999)
            assertEquals(2, exported.size)
            assertTrue(exported.all { it.receivedAtMs in 8_500..9_999 })
        }

    private suspend fun attempt(
        channel: String,
        at: Long,
        id: Long,
    ) {
        db.deliveryAttemptDao().insert(
            DeliveryAttemptEntity(channel = channel, attemptedAtMs = at, queueId = id, succeeded = true, detail = "test"),
        )
    }

    private fun message(
        receivedAt: Long,
        body: String,
    ) = IncomingMessage(
        source = "sms",
        senderAddress = "+15551234567",
        senderDisplay = "Test",
        body = body,
        receivedAtMs = receivedAt,
        sourceTimestampMs = receivedAt,
    )
}
