package dev.omp.mobile.collab

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ActiveTool(
    val toolCallId: String,
    val toolName: String,
    val args: JsonElement?,
    val intent: String?,
    val partialResult: JsonElement?,
    val startedAt: Long,
)

data class Notice(
    val id: Long,
    val level: String,
    val message: String,
    val at: Long,
)

data class LoadingProgress(val received: Int, val total: Int)

data class ReplicaSnapshot(
    val header: SessionHeader? = null,
    val state: SessionState? = null,
    val agents: List<AgentSnapshot> = emptyList(),
    /** Real `task:subagent:progress` payloads keyed by `progress.id`. */
    val subagentProgress: Map<String, SubagentProgressPayload> = emptyMap(),
    /** Real `task:subagent:lifecycle` payloads keyed by lifecycle id. */
    val subagentLifecycle: Map<String, SubagentLifecyclePayload> = emptyMap(),
    val readOnly: Boolean = false,
    val entries: List<SessionEntry> = emptyList(),
    /** Final tool results keyed by toolCallId, paired globally across the whole journal. */
    val toolResults: Map<String, WireMessage.ToolResult> = emptyMap(),
    val stream: WireMessage.Assistant? = null,
    val streamDone: Boolean = false,
    val activeTools: Map<String, ActiveTool> = emptyMap(),
    val working: Boolean = false,
    val loading: LoadingProgress? = null,
    val notices: List<Notice> = emptyList(),
    val uiRequest: CollabUiRequest? = null,
    val pendingUiRequests: Int = 0,
)

/**
 * Guest-side journal replica.
 *
 * Transcript semantics follow the web `/collab` client: every journal entry in
 * append order, including branch/compaction markers. `parentId` is retained but
 * never used to reconstruct an active branch.
 *
 * All methods must be called from the single client actor.
 */
class SessionReplica(private val clock: () -> Long = System::currentTimeMillis) {
    private var header: SessionHeader? = null
    private var state: SessionState? = null
    private var agents: List<AgentSnapshot> = emptyList()
    private var subagentProgress: Map<String, SubagentProgressPayload> = emptyMap()
    private var subagentLifecycle: Map<String, SubagentLifecyclePayload> = emptyMap()
    /** Bus ids absent from the latest agents frame; pruned when still absent next frame. */
    private var unlistedBusIds: Set<String> = emptySet()
    private var readOnly = false

    private val entries = mutableListOf<SessionEntry>()
    private val entryIndex = HashMap<String, Int>()
    private var publishedEntries: List<SessionEntry> = emptyList()
    private val toolResults = mutableMapOf<String, WireMessage.ToolResult>()
    private var publishedToolResults: Map<String, WireMessage.ToolResult> = emptyMap()

    private var stageEntries: MutableList<SessionEntry>? = null
    private var stageLive: MutableList<SessionEntry>? = null
    private var stageReceived = 0
    private var stageTotal = 0
    private var stageLiveDirty = false

    private var stream: WireMessage.Assistant? = null
    private var streamDone = false
    private var activeTools: Map<String, ActiveTool> = emptyMap()
    private var working = false

    private var notices: List<Notice> = emptyList()
    private var noticeSeq = 0L

    private var activeUiRequest: CollabUiRequest? = null
    private var uiQueue: List<CollabUiRequest> = emptyList()
    private var answeredUiReqIds: MutableSet<Long> = mutableSetOf()

    private var snapshot: ReplicaSnapshot = buildSnapshot()

    fun snapshot(): ReplicaSnapshot = snapshot

    /** Keeps the previous durable ledger visible until the new snapshot completes. */
    fun onWelcome(frame: HostFrame.Welcome) {
        header = frame.header
        state = frame.state
        agents = frame.agents
        subagentProgress = emptyMap()
        subagentLifecycle = emptyMap()
        unlistedBusIds = emptySet()
        readOnly = frame.readOnly
        stream = null
        streamDone = false
        activeTools = emptyMap()
        working = frame.state.isStreaming
        activeUiRequest = null
        uiQueue = emptyList()
        answeredUiReqIds = mutableSetOf()

        if (frame.entryCount <= 0) {
            entries.clear()
            entryIndex.clear()
            toolResults.clear()
            publishedEntries = emptyList()
            publishedToolResults = emptyMap()
            stageEntries = null
            stageLive = null
            stageReceived = 0
            stageTotal = 0
            stageLiveDirty = false
        } else {
            stageEntries = mutableListOf()
            stageLive = mutableListOf()
            stageReceived = 0
            stageTotal = frame.entryCount
            stageLiveDirty = false
        }
        publish()
    }

    fun onSnapshotChunk(frame: HostFrame.SnapshotChunk) {
        val staged = stageEntries ?: return
        staged.addAll(frame.entries)
        stageReceived += frame.entries.size
        if (!frame.final && stageReceived < stageTotal) {
            publish()
            return
        }
        promoteSnapshot(staged)
    }

    private fun promoteSnapshot(staged: List<SessionEntry>) {
        entries.clear()
        entryIndex.clear()
        toolResults.clear()
        publishedEntries = emptyList()
        publishedToolResults = emptyMap()
        for (entry in staged) appendToLedger(entry)
        val live = stageLive ?: emptyList()
        for (entry in live) appendToLedger(entry)
        stageEntries = null
        stageLive = null
        stageReceived = 0
        stageTotal = 0
        stageLiveDirty = false
        rebuildPublishedLedger()
        publish()
    }

    fun onEntry(entry: SessionEntry) {
        if (stageEntries != null) {
            stageLive?.add(entry)
            stageLiveDirty = true
            // A committed assistant row supersedes the finished ghost. A row with
            // a different identity leaves an active stream untouched.
            if (streamDone && entry is MessageEntry && entry.message is WireMessage.Assistant) clearGhost()
            publish()
            return
        }
        appendToLedger(entry)
        clearGhostForDurable(entry)
        rebuildPublishedLedger()
        publish()
    }

    private fun clearGhostForDurable(entry: SessionEntry) {
        if (entry !is MessageEntry) return
        val message = entry.message as? WireMessage.Assistant ?: return
        val ghost = stream ?: return
        if (streamDone) {
            clearGhost()
            return
        }
        // message_end may be lost on a flaky connection; the durable row is the
        // authoritative completion signal when timestamp+model line up.
        if (message.timestamp == ghost.timestamp && message.model == ghost.model) clearGhost()
    }

    private fun clearGhost() {
        stream = null
        streamDone = false
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> applyStream(event.message)
            is AgentEvent.MessageUpdate -> applyStream(event.message)
            is AgentEvent.MessageEnd -> {
                val message = event.message
                if (message is WireMessage.Assistant) {
                    stream = message
                    streamDone = true
                }
            }

            is AgentEvent.ToolStart -> {
                activeTools = activeTools + (event.toolCallId to ActiveTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    args = event.args,
                    intent = event.intent,
                    partialResult = null,
                    startedAt = clock(),
                ))
            }

            is AgentEvent.ToolUpdate -> {
                val existing = activeTools[event.toolCallId]
                activeTools = activeTools + (event.toolCallId to ActiveTool(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    args = event.args ?: existing?.args,
                    intent = existing?.intent,
                    partialResult = event.partialResult,
                    startedAt = existing?.startedAt ?: clock(),
                ))
            }

            is AgentEvent.ToolEnd -> activeTools = activeTools - event.toolCallId
            AgentEvent.AgentStart -> working = true
            AgentEvent.AgentEnd -> working = false
            is AgentEvent.Notice -> pushNotice(event.level, event.message)
            is AgentEvent.AutoRetryStart ->
                pushNotice("info", "retry ${event.attempt}/${event.maxAttempts}: ${event.errorMessage}")

            is AgentEvent.AutoRetryEnd ->
                if (!event.success) pushNotice("error", event.finalError ?: "retry failed")

            is AgentEvent.AutoCompactionStart -> pushNotice("info", "compacting context (${event.reason})")
            is AgentEvent.AutoCompactionEnd -> if (!event.skipped) {
                pushNotice(
                    "info",
                    when {
                        event.aborted -> "compaction aborted"
                        event.errorMessage != null -> "compaction failed: ${event.errorMessage}"
                        else -> "context compacted"
                    },
                )
            }

            else -> Unit
        }
        publish()
    }

    private fun applyStream(message: WireMessage) {
        if (message is WireMessage.Assistant) {
            stream = message
            streamDone = false
        }
    }

    fun onState(next: SessionState) {
        state = next
        working = next.isStreaming
        if (!next.isStreaming) {
            // Host idle means no tool can still be running even when a
            // tool_execution_end was dropped.
            activeTools = emptyMap()
            if (streamDone) clearGhost()
        }
        publish()
    }

    fun onAgents(next: List<AgentSnapshot>) {
        agents = next
        pruneBusMaps()
        publish()
    }

    /** Applies mirrored task-subagent bus traffic; unrelated channels are ignored. */
    fun onBus(channel: String, data: JsonElement?) {
        when (channel) {
            "task:subagent:progress" -> {
                val payload = parseSubagentProgress(data) ?: return
                subagentProgress = subagentProgress + (payload.progress.id to payload)
            }

            "task:subagent:lifecycle" -> {
                val payload = parseSubagentLifecycle(data) ?: return
                subagentLifecycle = subagentLifecycle + (payload.id to payload)
            }

            else -> return
        }
        publish()
    }

    /**
     * Drops bus payloads for agents the host no longer lists. Progress can
     * arrive before its agent registration, so a missing id survives one
     * agents frame and is pruned only if the next frame still omits it.
     */
    private fun pruneBusMaps() {
        if (subagentProgress.isEmpty() && subagentLifecycle.isEmpty()) {
            unlistedBusIds = emptySet()
            return
        }
        val listed = agents.mapTo(HashSet()) { it.id }
        val previouslyUnlisted = unlistedBusIds
        val unlisted = HashSet<String>()

        fun <V> prune(map: Map<String, V>): Map<String, V> {
            val retained = LinkedHashMap<String, V>(map.size)
            for ((id, value) in map) {
                if (!listed.contains(id)) {
                    if (previouslyUnlisted.contains(id)) continue
                    unlisted += id
                }
                retained[id] = value
            }
            return retained
        }

        subagentProgress = prune(subagentProgress)
        subagentLifecycle = prune(subagentLifecycle)
        unlistedBusIds = unlisted
    }

    fun onUiRequest(request: CollabUiRequest) {
        if (readOnly) return
        if (activeUiRequest == null) activeUiRequest = request
        else uiQueue = uiQueue + request
        publish()
    }

    fun onUiRequestEnd(reqId: Long) {
        if (activeUiRequest?.reqId == reqId) {
            activeUiRequest = uiQueue.firstOrNull()
            uiQueue = if (uiQueue.isEmpty()) emptyList() else uiQueue.drop(1)
            answeredUiReqIds.remove(reqId)
        } else {
            uiQueue = uiQueue.filterNot { it.reqId == reqId }
            answeredUiReqIds.remove(reqId)
        }
        publish()
    }

    fun activeUiRequest(): CollabUiRequest? = activeUiRequest

    fun markUiAnswered(reqId: Long): Boolean {
        if (activeUiRequest?.reqId != reqId) return false
        return answeredUiReqIds.add(reqId)
    }

    fun unmarkUiAnswered(reqId: Long) {
        answeredUiReqIds.remove(reqId)
    }

    /** Drops an in-flight snapshot; the durable ledger stays visible. */
    fun onDisconnected() {
        stageEntries = null
        stageLive = null
        stageReceived = 0
        stageTotal = 0
        stageLiveDirty = false
        activeUiRequest = null
        uiQueue = emptyList()
        answeredUiReqIds = mutableSetOf()
        publish()
    }

    /** Different pairing selected: never mix two sessions on screen. */
    fun clear() {
        header = null
        state = null
        agents = emptyList()
        subagentProgress = emptyMap()
        subagentLifecycle = emptyMap()
        unlistedBusIds = emptySet()
        readOnly = false
        entries.clear()
        entryIndex.clear()
        publishedEntries = emptyList()
        toolResults.clear()
        publishedToolResults = emptyMap()
        stageEntries = null
        stageLive = null
        stageReceived = 0
        stageTotal = 0
        stageLiveDirty = false
        stream = null
        streamDone = false
        activeTools = emptyMap()
        working = false
        notices = emptyList()
        activeUiRequest = null
        uiQueue = emptyList()
        answeredUiReqIds = mutableSetOf()
        publish()
    }

    fun pushNotice(level: String, message: String) {
        val notice = Notice(++noticeSeq, level, message, clock())
        val next = notices + notice
        notices = if (next.size > MAX_NOTICES) next.takeLast(MAX_NOTICES) else next
    }

    private fun appendToLedger(entry: SessionEntry) {
        val existing = entryIndex[entry.id]
        if (existing != null) {
            val old = entries[existing]
            removeToolResult(old)
            entries[existing] = entry
        } else {
            entryIndex[entry.id] = entries.size
            entries.add(entry)
        }
        if (entry is MessageEntry && entry.message is WireMessage.ToolResult) {
            toolResults[entry.message.toolCallId] = entry.message
        }
    }

    private fun removeToolResult(entry: SessionEntry) {
        if (entry is MessageEntry && entry.message is WireMessage.ToolResult) {
            toolResults.remove(entry.message.toolCallId)
        }
    }

    private fun rebuildPublishedLedger() {
        publishedEntries = entries.toList()
        publishedToolResults = if (toolResults.isEmpty()) emptyMap() else HashMap(toolResults)
    }

    private fun buildSnapshot(): ReplicaSnapshot = ReplicaSnapshot(
        header = header,
        state = state,
        agents = agents,
        subagentProgress = subagentProgress,
        subagentLifecycle = subagentLifecycle,
        readOnly = readOnly,
        entries = publishedEntries,
        toolResults = publishedToolResults,
        stream = stream,
        streamDone = streamDone,
        activeTools = activeTools,
        working = working,
        loading = stageEntries?.let { LoadingProgress(stageReceived, stageTotal) },
        notices = notices,
        uiRequest = activeUiRequest,
        pendingUiRequests = uiQueue.size,
    )

    private fun publish() {
        if (stageLiveDirty) {
            // Live entries buffered behind a snapshot are counted separately
            // from wire snapshot progress; keep them out of the ledger until
            // promotion.
            stageLiveDirty = false
        }
        snapshot = buildSnapshot()
    }

    private companion object {
        const val MAX_NOTICES = 50
    }
}

/** `details.from` for a host-injected collab prompt, falling back to "guest". */
fun CustomMessageEntry.fromParticipant(): String {
    val details = details
    if (details != null) {
        val from = runCatching {
            details.jsonObject["from"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (from != null) return from
    }
    return "guest"
}
