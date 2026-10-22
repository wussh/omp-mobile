package dev.omp.mobile.collab

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private class FakeTransport(private val key: ByteArray) : TransportFactory {
    val sessions = mutableListOf<FakeSession>()

    override fun create(url: String, listener: TransportListener): TransportSession {
        val session = FakeSession(url, listener, key)
        sessions += session
        return session
    }
}

private class FakeSession(
    val url: String,
    private val listener: TransportListener,
    key: ByteArray,
) : TransportSession {
    private val cipher = RoomCipher.fromKey(key)
    private var closed = false
    val sent = mutableListOf<ByteArray>()

    override fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        sent += bytes
        return true
    }

    override fun close(code: Int, reason: String) {
        closed = true
    }

    fun open() = listener.onOpen()

    fun deliver(frame: String) {
        listener.onBinary(packEnvelope(0, cipher.seal(frame)))
    }

    fun deliverRaw(bytes: ByteArray) = listener.onBinary(bytes)

    fun text(text: String) = listener.onText(text)

    fun remoteClose(code: Int, reason: String = "") {
        listener.onClosing(code, reason)
        listener.onClosed(code, reason)
    }

    fun failure() = listener.onFailure(IOException("boom"))

    fun guestFrames(): List<String> {
        return sent.map { cipher.open(unpackEnvelope(it)!!.payload) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollabClientTest {
    private val key = ByteArray(32) { it.toByte() }
    private val token = ByteArray(16) { (0xf0 + it).toByte() }

    private fun credentials(withToken: Boolean = true) = PairingCredentials(
        relayOrigin = "ws://localhost:7466",
        roomId = "AbCdEf123456_-Xy",
        key = key,
        writeToken = if (withToken) token else null,
    )

    private fun welcomeJson(
        entryCount: Int,
        readOnly: Boolean = false,
        proto: Int = COLLAB_PROTO,
    ): String = buildString {
        append("""{"t":"welcome","proto":$proto,"header":{"id":"s1","title":"Project","cwd":"/work/demo"},""")
        append(""""state":{"isStreaming":false,"queuedMessageCount":0,"cwd":"/work/demo","participants":[]},""")
        append(""""agents":[],"entryCount":$entryCount""")
        if (readOnly) append(""","readOnly":true""")
        append("}")
    }

    private fun userEntryJson(id: String): String =
        """{"id":"$id","parentId":null,"timestamp":"t","type":"message","message":{"role":"user","content":"$id","timestamp":1}}"""

    private fun chunkJson(ids: List<String>, final: Boolean): String =
        """{"t":"snapshot-chunk","entries":[${ids.joinToString(",") { userEntryJson(it) }}],"final":$final}"""

    private fun assistantUpdate(text: String, timestamp: Long = 1): String =
        """{"t":"event","event":{"type":"message_update","message":{"role":"assistant","content":[{"type":"text","text":"$text"}],"model":"m","usage":{},"stopReason":"stop","timestamp":$timestamp}}}"""

    private fun byeJson(reason: String = "host stopped"): String =
        """{"t":"bye","reason":"$reason"}"""

    private suspend fun TestScope.connected(
        client: CollabClient,
        transport: FakeTransport,
        entryCount: Int = 0,
        readOnly: Boolean = false,
        withToken: Boolean = true,
    ): FakeSession {
        client.connect(credentials(withToken), "Android")
        runCurrent()
        val session = transport.sessions.last()
        session.open()
        runCurrent()
        session.deliver(welcomeJson(entryCount, readOnly))
        runCurrent()
        return session
    }

    @Test
    fun welcomeWithoutSnapshotIsConnectedOnlyAfterHandshake() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        val session = transport.sessions.single()
        assertEquals(ConnectionStatus.Connecting, client.state.value.status)

        session.open()
        runCurrent()
        val hello = session.guestFrames().single()
        assertTrue(hello.contains("\"t\":\"hello\""))
        assertTrue(hello.contains("\"proto\":3"))
        assertTrue(hello.contains("\"name\":\"Android\""))
        assertTrue(hello.contains("\"writeToken\":\"8PHy8_T19vf4-fr7_P3-_w\""))

        session.deliver(welcomeJson(0))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
        assertEquals("Project", client.state.value.replica.header?.title)
    }

    @Test
    fun snapshotProgressRequiresEveryEntryOrDefaultFinal() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport, entryCount = 2)
        assertEquals(ConnectionStatus.Syncing(0, 2), client.state.value.status)

        session.deliver(chunkJson(listOf("A"), final = false))
        runCurrent()
        assertEquals(ConnectionStatus.Syncing(1, 2), client.state.value.status)
        session.deliver(chunkJson(listOf("B"), final = false))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
        assertEquals(listOf("A", "B"), client.state.value.replica.entries.map { it.id })
    }

    @Test
    fun entriesBeforeWelcomeAreIgnored() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        val session = transport.sessions.single()
        session.open()
        runCurrent()
        session.deliver("""{"t":"entry","entry":${userEntryJson("early")}}""")
        runCurrent()
        assertTrue(client.state.value.replica.entries.isEmpty())

        session.deliver(welcomeJson(1))
        session.deliver(chunkJson(listOf("A"), final = true))
        runCurrent()
        assertEquals(listOf("A"), client.state.value.replica.entries.map { it.id })
    }

    @Test
    fun readOnlyAndTokenlessLinksRejectMutations() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport, readOnly = true)
        assertEquals(SendResult.ReadOnly, client.sendPrompt("hello"))
        assertEquals(SendResult.ReadOnly, client.interrupt())
        assertTrue(session.guestFrames().none { it.contains("\"t\":\"prompt\"") })

        val transport2 = FakeTransport(key)
        val client2 = CollabClient(backgroundScope, transport2, random = Random(1))
        val session2 = connected(client2, transport2, withToken = false)
        assertEquals(SendResult.ReadOnly, client2.sendPrompt("hello"))
        assertEquals(1, session2.guestFrames().size) // hello only
    }

    @Test
    fun promptAndInterruptAreQueuedExactlyOnce() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        assertEquals(SendResult.Queued, client.sendPrompt("  exact text  "))
        assertEquals(SendResult.Queued, client.interrupt())
        val frames = session.guestFrames()
        assertTrue(frames.any { it == """{"t":"prompt","text":"  exact text  "}""" })
        assertTrue(frames.any { it == """{"t":"abort"}""" })
        assertEquals(SendResult.Rejected, client.sendPrompt("   "))
    }

    @Test
    fun promptCarriesImagesBesideOptionalText() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        val image = PromptImage("YWJj", "image/jpeg")
        assertEquals(SendResult.Queued, client.sendPrompt("", listOf(image)))
        assertTrue(
            session.guestFrames().any {
                it == """{"t":"prompt","text":"","images":[{"type":"image","data":"YWJj","mimeType":"image/jpeg"}]}"""
            },
        )
    }

    @Test
    fun rejectedSocketSendReconnectsAndResyncsWithoutReplayingPrompt() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val old = connected(client, transport)
        old.deliver("""{"t":"entry","entry":${userEntryJson("retained")}}""")
        runCurrent()
        // A socket may refuse enqueue before its close callback reaches the actor.
        old.close(1000, "")
        assertEquals(SendResult.Rejected, client.sendPrompt("do not replay"))
        val reconnecting = client.state.value.status
        assertTrue(reconnecting is ConnectionStatus.Reconnecting)
        assertEquals(listOf("retained"), client.state.value.replica.entries.map { it.id })
        advanceTimeBy((reconnecting as ConnectionStatus.Reconnecting).nextDelayMs + 1)
        runCurrent()
        val replacement = transport.sessions.last()
        assertEquals(2, transport.sessions.size)
        replacement.open()
        runCurrent()
        replacement.deliver(welcomeJson(1))
        replacement.deliver(chunkJson(listOf("resynced"), final = true))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
        assertEquals(listOf("resynced"), client.state.value.replica.entries.map { it.id })
        assertEquals(1, replacement.guestFrames().size)
        assertTrue(replacement.guestFrames().single().contains(""""t":"hello""""))
    }

    @Test
    fun initialMissingRoomIsTerminal() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        val session = transport.sessions.single()
        session.open()
        runCurrent()
        session.remoteClose(4004, "no such room")
        runCurrent()
        val status = client.state.value.status
        assertTrue(status is ConnectionStatus.Failed)
        assertEquals(FailureKind.ROOM_NOT_FOUND, (status as ConnectionStatus.Failed).kind)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.sessions.size)
    }

    @Test
    fun roomRecreationRaceRecoversThrough4001And4004() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        transport.sessions.single().open()
        runCurrent()
        transport.sessions.single().remoteClose(4001, "room closed")
        runCurrent()
        assertTrue(client.state.value.status is ConnectionStatus.Reconnecting)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, transport.sessions.size)
        transport.sessions.last().open()
        runCurrent()
        transport.sessions.last().remoteClose(4004, "no such room")
        runCurrent()
        assertTrue(client.state.value.status is ConnectionStatus.Reconnecting)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, transport.sessions.size)
        transport.sessions.last().open()
        runCurrent()
        transport.sessions.last().deliver(welcomeJson(0))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
    }

    @Test
    fun protocolMismatchAndBadFramesAreTerminal() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        val session = transport.sessions.single()
        session.open()
        runCurrent()
        session.deliver(welcomeJson(0, proto = 2))
        runCurrent()
        assertEquals(
            FailureKind.PROTOCOL_MISMATCH,
            (client.state.value.status as ConnectionStatus.Failed).kind,
        )

        val transport2 = FakeTransport(key)
        val client2 = CollabClient(backgroundScope, transport2, random = Random(1))
        client2.connect(credentials(), "Android")
        runCurrent()
        val session2 = transport2.sessions.single()
        session2.open()
        runCurrent()
        session2.deliverRaw(packEnvelope(0, ByteArray(20) { 9 }))
        runCurrent()
        assertEquals(FailureKind.PROTOCOL_ERROR, (client2.state.value.status as ConnectionStatus.Failed).kind)
    }

    @Test
    fun preWelcomeHostErrorIsJoinRejected() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        val session = transport.sessions.single()
        session.open()
        runCurrent()
        session.deliver("""{"t":"error","message":"bad token"}""")
        runCurrent()
        assertEquals(FailureKind.JOIN_REJECTED, (client.state.value.status as ConnectionStatus.Failed).kind)
    }

    @Test
    fun encryptedByeIsTerminalAndDoesNotReconnectOnResume() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        session.deliver(byeJson())
        runCurrent()
        assertEquals(ConnectionStatus.Ended("host stopped"), client.state.value.status)

        advanceTimeBy(120_000)
        client.resume()
        runCurrent()
        assertEquals(1, transport.sessions.size)
        assertTrue(client.state.value.status is ConnectionStatus.Ended)
    }

    @Test
    fun unexpectedCloseRetriesAndPromptIsNotReplayed() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val first = connected(client, transport)
        assertEquals(SendResult.Queued, client.sendPrompt("do once"))
        first.remoteClose(1006, "gone")
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, transport.sessions.size)
        val second = transport.sessions.last()
        second.open()
        runCurrent()
        val frames = second.guestFrames()
        assertTrue(frames.single().contains("\"t\":\"hello\""))
        assertTrue(frames.none { it.contains("\"t\":\"prompt\"") })
    }

    @Test
    fun pausedSessionDoesNotReconnectUntilResume() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        connected(client, transport)
        client.pause()
        runCurrent()
        assertEquals(ConnectionStatus.Paused, client.state.value.status)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.sessions.size)

        client.resume()
        runCurrent()
        assertEquals(2, transport.sessions.size)
        transport.sessions.last().open()
        runCurrent()
        transport.sessions.last().deliver(welcomeJson(0))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
    }

    @Test
    fun networkLossWaitsWithoutHotLoop() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        connected(client, transport)
        client.networkLost()
        runCurrent()
        assertEquals(ConnectionStatus.WaitingForNetwork, client.state.value.status)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, transport.sessions.size)

        client.networkChanged()
        runCurrent()
        assertEquals(2, transport.sessions.size)
    }

    @Test
    fun welcomeTimeoutRetriesWithBackoff() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        transport.sessions.single().open()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        val status = client.state.value.status
        assertTrue(status is ConnectionStatus.Reconnecting)
        assertTrue((status as ConnectionStatus.Reconnecting).nextDelayMs <= 30_000)

        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(2, transport.sessions.size)
    }

    @Test
    fun backoffStaysCappedWithoutOverflow() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        client.connect(credentials(), "Android")
        runCurrent()
        transport.sessions.single().open()
        runCurrent()

        repeat(40) {
            transport.sessions.last().remoteClose(1006, "drop")
            runCurrent()
            val status = client.state.value.status
            assertTrue(status is ConnectionStatus.Reconnecting)
            val delay = (status as ConnectionStatus.Reconnecting).nextDelayMs
            // The 30s cap applies before the 0.75..1.25 jitter.
            assertTrue("delay=$delay", delay in 1..37_500)
            advanceTimeBy(delay + 1)
            runCurrent()
        }
        assertTrue(client.state.value.status is ConnectionStatus.Reconnecting)
    }

    @Test
    fun textRoomClosedIsOnlyAHint() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        session.text("""{"t":"room-closed"}""")
        runCurrent()
        assertTrue(client.state.value.status is ConnectionStatus.Reconnecting)
        assertFalse(client.state.value.status is ConnectionStatus.Ended)
    }

    @Test
    fun selectAndEditorResponsesUseExactStringsAndOneShot() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        val select =
            """{"t":"ui-request","request":{"kind":"select","reqId":1,"title":"Pick","options":["Option A",{"label":"Next →","description":"continue"}],"selectionMarker":"checkbox","checkedIndices":[0],"markableCount":1}}"""
        session.deliver(select)
        runCurrent()
        assertEquals(1L, client.state.value.replica.uiRequest?.reqId)

        assertEquals(SendResult.Queued, client.respond(1, "Next →"))
        assertEquals(SendResult.Rejected, client.respond(1, "Next →"))
        val frames = session.guestFrames()
        assertEquals(1, frames.count { it.contains("\"t\":\"ui-response\"") })
        assertTrue(frames.any { it == """{"t":"ui-response","reqId":1,"value":"Next →"}""" })

        session.deliver("""{"t":"ui-request-end","reqId":1}""")
        runCurrent()
        assertEquals(null, client.state.value.replica.uiRequest)

        session.deliver("""{"t":"ui-request","request":{"kind":"editor","reqId":2,"title":"Edit","prefill":"abc"}}""")
        runCurrent()
        assertEquals(SendResult.Queued, client.respond(2, null))
        assertTrue(session.guestFrames().any { it == """{"t":"ui-response","reqId":2}""" })
    }

    @Test
    fun uiRequestEndOfQueuedRequestDoesNotDismissActiveOne() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)
        session.deliver("""{"t":"ui-request","request":{"kind":"editor","reqId":1,"title":"One"}}""")
        session.deliver("""{"t":"ui-request","request":{"kind":"editor","reqId":2,"title":"Two"}}""")
        runCurrent()
        session.deliver("""{"t":"ui-request-end","reqId":2}""")
        runCurrent()
        assertEquals(1L, client.state.value.replica.uiRequest?.reqId)
    }

    @Test
    fun readOnlyDefensivelyIgnoresUiRequests() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport, readOnly = true)
        session.deliver("""{"t":"ui-request","request":{"kind":"editor","reqId":1,"title":"One"}}""")
        runCurrent()
        assertEquals(null, client.state.value.replica.uiRequest)
    }

    @Test
    fun manualDisconnectStaysIdle() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        connected(client, transport)
        client.disconnect()
        runCurrent()
        assertEquals(ConnectionStatus.Idle, client.state.value.status)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.sessions.size)
    }

    @Test
    fun agentCommandsRequireControlAndSerializeExactlyOnce() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)

        assertEquals(SendResult.Queued, client.sendAgentCommand(AgentCommand.CHAT, "a1", "hello agent"))
        assertEquals(SendResult.Queued, client.sendAgentCommand(AgentCommand.KILL, "a1"))
        assertEquals(SendResult.Queued, client.sendAgentCommand(AgentCommand.REVIVE, "a1", "ignored"))
        val frames = session.guestFrames().filter { it.contains("\"t\":\"agent-cmd\"") }
        assertEquals(
            listOf(
                """{"t":"agent-cmd","cmd":"chat","agentId":"a1","text":"hello agent"}""",
                """{"t":"agent-cmd","cmd":"kill","agentId":"a1"}""",
                """{"t":"agent-cmd","cmd":"revive","agentId":"a1"}""",
            ),
            frames,
        )

        assertEquals(SendResult.Rejected, client.sendAgentCommand(AgentCommand.CHAT, "a1", "   "))
        assertEquals(SendResult.Rejected, client.sendAgentCommand(AgentCommand.KILL, " "))
        assertEquals(3, session.guestFrames().count { it.contains("\"t\":\"agent-cmd\"") })

        val viewTransport = FakeTransport(key)
        val viewClient = CollabClient(backgroundScope, viewTransport, random = Random(1))
        val viewSession = connected(viewClient, viewTransport, withToken = false)
        assertEquals(SendResult.ReadOnly, viewClient.sendAgentCommand(AgentCommand.KILL, "a1"))
        assertTrue(viewSession.guestFrames().none { it.contains("\"t\":\"agent-cmd\"") })

        val idleClient = CollabClient(backgroundScope, FakeTransport(key), random = Random(1))
        assertEquals(
            SendResult.NotConnected,
            idleClient.sendAgentCommand(AgentCommand.KILL, "a1"),
        )
    }

    @Test
    fun queuedCommandsBindToTheActivationTheyWereEnqueuedFor() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val oldSession = connected(client, transport)

        // The switch is queued but not yet processed when the stale command captures
        // the old activation id.
        client.connect(credentials(), "Android")
        val prompt = async(start = CoroutineStart.UNDISPATCHED) { client.sendPrompt("stale draft") }
        val transcript = async(start = CoroutineStart.UNDISPATCHED) { client.fetchTranscript("a1", 0) }
        runCurrent()
        assertEquals(SendResult.Rejected, prompt.await())
        assertEquals(TranscriptResult.Unavailable, transcript.await())
        assertTrue(oldSession.guestFrames().none { it.contains("\"t\":\"prompt\"") })
        assertEquals(2, transport.sessions.size)
    }

    @Test
    fun transcriptRequestsCorrelateByReqIdAndIgnoreStaleReplies() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)

        val first = async { client.fetchTranscript("a1", 0) }
        val second = async { client.fetchTranscript("a2", 128) }
        runCurrent()
        val requests = session.guestFrames().filter { it.contains("\"t\":\"fetch-transcript\"") }
        assertEquals(2, requests.size)
        val reqIds = requests.map { frame -> frame.reqId() }
        assertEquals(
            """{"t":"fetch-transcript","reqId":${reqIds[0]},"agentId":"a1","fromByte":0}""",
            requests[0],
        )
        assertEquals(
            """{"t":"fetch-transcript","reqId":${reqIds[1]},"agentId":"a2","fromByte":128}""",
            requests[1],
        )

        session.deliver("""{"t":"transcript","reqId":999,"text":"stale","newSize":1}""")
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        session.deliver("""{"t":"transcript","reqId":${reqIds[1]},"text":"second","newSize":200}""")
        session.deliver("""{"t":"transcript","reqId":${reqIds[0]},"text":"first","newSize":128}""")
        runCurrent()
        assertEquals(TranscriptResult.Rows("first", 128), first.await())
        assertEquals(TranscriptResult.Rows("second", 200), second.await())

        // Duplicate replies for consumed reqIds must not disturb anything.
        session.deliver("""{"t":"transcript","reqId":${reqIds[0]},"text":"again","newSize":256}""")
        runCurrent()
    }

    @Test
    fun transcriptTimeoutAndDisconnectResolvePendingRequests() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport)

        val pending = async { client.fetchTranscript("a1", 0) }
        runCurrent()
        val firstReqId = session.guestFrames().first { it.contains("\"t\":\"fetch-transcript\"") }.reqId()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(TranscriptResult.Timeout, pending.await())

        val retry = async { client.fetchTranscript("a1", 0) }
        runCurrent()
        val frames = session.guestFrames().filter { it.contains("\"t\":\"fetch-transcript\"") }
        val retryReqId = frames.last().reqId()
        assertTrue(retryReqId > firstReqId)
        // A late reply to the timed-out request must not resolve the retry.
        session.deliver("""{"t":"transcript","reqId":$firstReqId,"text":"late","newSize":10}""")
        runCurrent()
        assertFalse(retry.isCompleted)
        session.deliver("""{"t":"transcript","reqId":$retryReqId,"text":"ok","newSize":10}""")
        runCurrent()
        assertEquals(TranscriptResult.Rows("ok", 10), retry.await())

        val lost = async { client.fetchTranscript("a1", 0) }
        runCurrent()
        client.disconnect()
        runCurrent()
        assertEquals(TranscriptResult.Unavailable, lost.await())
    }

    @Test
    fun viewSessionReadsTranscriptAndHostErrorsAreTyped() = runTest {
        val transport = FakeTransport(key)
        val client = CollabClient(backgroundScope, transport, random = Random(1))
        val session = connected(client, transport, withToken = false)

        assertEquals(TranscriptResult.Rejected, client.fetchTranscript(" ", 0))
        assertEquals(TranscriptResult.Rejected, client.fetchTranscript("a1", -1))

        val pending = async { client.fetchTranscript("a1", 64) }
        runCurrent()
        val reqId = session.guestFrames().first { it.contains("\"t\":\"fetch-transcript\"") }.reqId()
        session.deliver(
            """{"t":"transcript","reqId":$reqId,"text":"","newSize":64,"error":"session file missing"}""",
        )
        runCurrent()
        assertEquals(TranscriptResult.HostError("session file missing"), pending.await())

        client.pause()
        runCurrent()
        assertEquals(TranscriptResult.Unavailable, client.fetchTranscript("a1", 0))
    }

    private fun String.reqId(): Long =
        Regex("\"reqId\":(\\d+)").find(this)!!.groupValues[1].toLong()

    @Test
    fun realOkHttpTransportCompletesHandshake() {
    val key = ByteArray(32) { it.toByte() }
    val hostCipher = RoomCipher.fromKey(key)
    val server = MockWebServer()
    val closed = CountDownLatch(1)
    server.enqueue(
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val envelope = unpackEnvelope(bytes.toByteArray()) ?: return
                val hello = hostCipher.open(envelope.payload)
                if (hello.contains("\"t\":\"hello\"")) {
                    val welcome =
                        """{"t":"welcome","proto":3,"header":{"id":"s1","title":"Real","cwd":"/w"},"state":{"isStreaming":false,"queuedMessageCount":0,"cwd":"/w","participants":[]},"agents":[],"entryCount":0}"""
                    webSocket.send(ByteString.of(*packEnvelope(0, hostCipher.seal(welcome))))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.countDown()
            }
        }),
    )
    server.start()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    val client = CollabClient(scope, OkHttpCollabTransport(), random = Random(1))
    try {
        client.connect(
            PairingCredentials(
                relayOrigin = "ws://127.0.0.1:${server.port}",
                roomId = "AbCdEf123456_-Xy",
                key = key,
                writeToken = ByteArray(16),
            ),
            "Android",
        )
        val connected = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                client.state.first { it.status is ConnectionStatus.Connected }
            }
        }
        assertEquals("Real", connected.replica.header?.title)
    } finally {
        try {
            client.disconnect()
            assertTrue("WebSocket close handshake did not complete", closed.await(5, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }
}
}
