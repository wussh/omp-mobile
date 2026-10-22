package dev.omp.mobile.collab

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun realOkHttpTransportCompletesHandshake() {
    val key = ByteArray(32) { it.toByte() }
    val hostCipher = RoomCipher.fromKey(key)
    val server = MockWebServer()
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
        client.disconnect()
        server.shutdown()
    }
}
}
