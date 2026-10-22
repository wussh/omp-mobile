package dev.omp.mobile.collab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReplicaTest {
    private fun header() = SessionHeader(id = "s1", title = "Session", cwd = "/work")

    private fun state(streaming: Boolean = false) = SessionState(isStreaming = streaming, cwd = "/work")

    private fun welcome(count: Int, readOnly: Boolean = false) = HostFrame.Welcome(
        proto = COLLAB_PROTO,
        header = header(),
        state = state(),
        agents = emptyList(),
        entryCount = count,
        readOnly = readOnly,
    )

    private fun user(id: String, text: String = id) = MessageEntry(
        id = id,
        parentId = null,
        timestamp = "t-$id",
        message = WireMessage.User(MessageContent.Text(text), synthetic = false, timestamp = 1),
    )

    private fun assistant(
        id: String,
        text: String,
        timestamp: Long = 100,
        model: String = "m",
        stop: String = "stop",
    ) = MessageEntry(
        id = id,
        parentId = null,
        timestamp = "t-$id",
        message = WireMessage.Assistant(
            content = listOf(ContentBlock.Text(text)),
            model = model,
            usage = WireUsage(),
            stopReason = stop,
            errorMessage = null,
            timestamp = timestamp,
        ),
    )

    private fun toolResult(id: String, callId: String, text: String = "ok") = MessageEntry(
        id = id,
        parentId = null,
        timestamp = "t-$id",
        message = WireMessage.ToolResult(
            toolCallId = callId,
            toolName = "bash",
            content = listOf(ContentBlock.Text(text)),
            details = null,
            isError = false,
            timestamp = 5,
        ),
    )

    @Test
    fun snapshotStaysAtomicAndAppliesLiveEntriesAfterPromotion() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEntry(user("A"))
        assertEquals(listOf("A"), replica.snapshot().entries.map { it.id })

        replica.onWelcome(welcome(2))
        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(user("B")), final = false))
        replica.onEntry(user("D"))
        assertEquals(listOf("A"), replica.snapshot().entries.map { it.id })
        assertEquals(LoadingProgress(1, 2), replica.snapshot().loading)

        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(user("C")), final = true))
        assertEquals(listOf("B", "C", "D"), replica.snapshot().entries.map { it.id })
        assertNull(replica.snapshot().loading)

        replica.onEntry(user("D"))
        assertEquals(listOf("B", "C", "D"), replica.snapshot().entries.map { it.id })
    }

    @Test
    fun emptySnapshotClearsLedger() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEntry(user("A"))
        replica.onWelcome(welcome(0))
        assertTrue(replica.snapshot().entries.isEmpty())
        assertNull(replica.snapshot().loading)
    }

    @Test
    fun incompleteSnapshotIsDroppedOnNewWelcome() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEntry(user("A"))
        replica.onWelcome(welcome(2))
        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(user("B")), final = false))
        replica.onDisconnected()
        assertNull(replica.snapshot().loading)
        assertEquals(listOf("A"), replica.snapshot().entries.map { it.id })

        replica.onWelcome(welcome(1))
        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(user("C")), final = true))
        assertEquals(listOf("C"), replica.snapshot().entries.map { it.id })
    }

    @Test
    fun unknownEntryCountsTowardProgressButIsNotRendered() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(2))
        val unknown = UnknownEntry("u1", null, "t", "future_entry")
        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(unknown), final = false))
        assertEquals(LoadingProgress(1, 2), replica.snapshot().loading)
        replica.onSnapshotChunk(HostFrame.SnapshotChunk(listOf(user("A")), final = true))
        assertEquals(listOf("u1", "A"), replica.snapshot().entries.map { it.id })
        assertTrue(replica.snapshot().entries.first() is UnknownEntry)
    }

    @Test
    fun messageUpdateReplacesFullGhostInsteadOfAppending() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEvent(AgentEvent.MessageUpdate(assistant("partial", "hel").message))
        replica.onEvent(AgentEvent.MessageUpdate(assistant("partial", "hello").message))
        assertEquals("hello", (replica.snapshot().stream!!.content.single() as ContentBlock.Text).text)
    }

    @Test
    fun durableAssistantClearsGhostEvenWithoutMessageEnd() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEvent(AgentEvent.MessageStart(assistant("ghost", "hi", timestamp = 42, model = "x").message))
        replica.onEntry(assistant("full", "hi", timestamp = 42, model = "x"))
        assertNull(replica.snapshot().stream)
    }

    @Test
    fun differentIdentityDoesNotClearActiveStream() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEvent(AgentEvent.MessageUpdate(assistant("ghost", "live", timestamp = 99, model = "x").message))
        replica.onEntry(assistant("old", "old", timestamp = 1, model = "x"))
        assertEquals("live", (replica.snapshot().stream!!.content.single() as ContentBlock.Text).text)

        replica.onEntry(assistant("done", "live", timestamp = 99, model = "x"))
        assertNull(replica.snapshot().stream)
    }

    @Test
    fun toolResultPairsGloballyBeyondWindow() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        val call = ContentBlock.ToolCall("call-1", "bash", null, null)
        val assistant = MessageEntry(
            id = "a1",
            parentId = null,
            timestamp = "t",
            message = WireMessage.Assistant(
                content = listOf(call),
                model = "m",
                usage = WireUsage(),
                stopReason = "toolUse",
                errorMessage = null,
                timestamp = 1,
            ),
        )
        replica.onEntry(assistant)
        repeat(150) { replica.onEntry(user("u$it")) }
        replica.onEntry(toolResult("r1", "call-1", "done"))
        assertEquals("done", (replica.snapshot().toolResults["call-1"]!!.content.single() as ContentBlock.Text).text)
    }

    @Test
    fun idleStateClearsDanglingToolOverlayAndFinishedGhost() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEvent(AgentEvent.ToolStart("call", "bash", null, null))
        replica.onEvent(AgentEvent.MessageEnd(assistant("ghost", "done").message))
        assertEquals(1, replica.snapshot().activeTools.size)
        assertTrue(replica.snapshot().streamDone)

        replica.onState(state(streaming = false))
        assertTrue(replica.snapshot().activeTools.isEmpty())
        assertNull(replica.snapshot().stream)
    }

    @Test
    fun uiRequestsQueueAndDismissWithoutSendingCancel() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        val first = CollabUiRequest.Select(1, "Q1", listOf(SelectOption("A", null)), 0, "radio", emptyList(), 1, null)
        val second = CollabUiRequest.Editor(2, "Q2", "prefill")
        replica.onUiRequest(first)
        replica.onUiRequest(second)
        assertEquals(first, replica.snapshot().uiRequest)
        assertEquals(1, replica.snapshot().pendingUiRequests)

        assertTrue(replica.markUiAnswered(1))
        assertEquals(false, replica.markUiAnswered(1))
        replica.onUiRequestEnd(1)
        assertEquals(second, replica.snapshot().uiRequest)

        // An end for a request that is still queued removes it without clearing the active one.
        replica.onUiRequest(CollabUiRequest.Editor(3, "Q3", null))
        replica.onUiRequestEnd(3)
        assertEquals(second, replica.snapshot().uiRequest)
    }

    @Test
    fun readOnlyReplicaIgnoresUiRequests() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0, readOnly = true))
        replica.onUiRequest(CollabUiRequest.Editor(1, "Q", null))
        assertNull(replica.snapshot().uiRequest)
    }

    @Test
    fun clearDropsEverythingForAnotherPairing() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEntry(user("A"))
        replica.clear()
        assertTrue(replica.snapshot().entries.isEmpty())
        assertNull(replica.snapshot().header)
    }

    @Test
    fun repeatedIdUpsertsInPlace() {
        val replica = SessionReplica()
        replica.onWelcome(welcome(0))
        replica.onEntry(user("A"))
        replica.onEntry(user("B"))
        replica.onEntry(user("A", "updated"))
        assertEquals(listOf("A", "B"), replica.snapshot().entries.map { it.id })
        assertEquals(
            "updated",
            ((replica.snapshot().entries.first() as MessageEntry).message as WireMessage.User).content.text(),
        )
    }

    @Test
    fun malformedKnownFrameIsAProtocolErrorNotAnEmptyTranscript() {
        val error = kotlin.runCatching { parseHostFrame("""{"t":"welcome","proto":3}""") }.exceptionOrNull()
        assertTrue(error is WireFormatException)
    }
}

private fun MessageContent.text(): String = when (this) {
    is MessageContent.Text -> text
    is MessageContent.Blocks -> blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
}
