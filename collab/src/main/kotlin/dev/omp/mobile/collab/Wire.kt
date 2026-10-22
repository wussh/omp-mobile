package dev.omp.mobile.collab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Wire JSON codec. Wire names stay camelCase; unknown fields are tolerated. */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    encodeDefaults = true
}

const val COLLAB_PROMPT_MESSAGE_TYPE = "collab-prompt"

// ── Content blocks ─────────────────────────────────────────────────────────

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val data: String, val mimeType: String) : ContentBlock
    data class Thinking(val thinking: String) : ContentBlock
    data class RedactedThinking(val data: String) : ContentBlock
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonElement?,
        val intent: String?,
    ) : ContentBlock
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class Blocks(val blocks: List<ContentBlock>) : MessageContent
}

@Serializable
data class WireUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val totalTokens: Long = 0,
)

sealed interface WireMessage {
    val timestamp: Long

    data class User(
        val content: MessageContent,
        val synthetic: Boolean,
        override val timestamp: Long,
    ) : WireMessage

    data class Developer(
        val content: MessageContent,
        override val timestamp: Long,
    ) : WireMessage

    data class Assistant(
        val content: List<ContentBlock>,
        val model: String,
        val usage: WireUsage,
        val stopReason: String,
        val errorMessage: String?,
        override val timestamp: Long,
    ) : WireMessage

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: List<ContentBlock>,
        val details: JsonElement?,
        val isError: Boolean,
        override val timestamp: Long,
    ) : WireMessage
}

// ── Session entries ────────────────────────────────────────────────────────

@Serializable
data class SessionHeader(
    val type: String = "session",
    val id: String,
    val title: String? = null,
    val timestamp: String = "",
    val cwd: String = "",
)

sealed interface SessionEntry {
    val id: String
    val parentId: String?
    val timestamp: String
}

data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: WireMessage,
) : SessionEntry

data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: MessageContent,
    val details: JsonElement?,
    val display: Boolean,
) : SessionEntry

data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val shortSummary: String?,
    val tokensBefore: Long,
) : SessionEntry

data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
) : SessionEntry

data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val model: String,
) : SessionEntry

data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String?,
) : SessionEntry

/** Counted for snapshot progress, never rendered. */
data class UnknownEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val type: String?,
) : SessionEntry

// ── State and agents ───────────────────────────────────────────────────────

@Serializable
data class WireModel(
    val id: String = "",
    val name: String = "",
    val provider: String = "",
    val contextWindow: Long? = null,
)

@Serializable
data class ContextUsage(
    val tokens: Long? = null,
    val contextWindow: Long? = null,
    val percent: Double? = null,
)

@Serializable
data class Participant(
    val name: String = "",
    val role: String = "",
    val readOnly: Boolean? = null,
)

@Serializable
data class SessionState(
    val isStreaming: Boolean = false,
    val queuedMessageCount: Int = 0,
    val sessionName: String? = null,
    val cwd: String = "",
    val model: WireModel? = null,
    val thinkingLevel: String? = null,
    val contextUsage: ContextUsage? = null,
    val participants: List<Participant> = emptyList(),
    val isAborting: Boolean? = null,
)

@Serializable
data class AgentSnapshot(
    val id: String,
    val displayName: String = "",
    val kind: String = "main",
    val parentId: String? = null,
    val status: String = "idle",
    val hasSessionFile: Boolean = false,
    val createdAt: Long = 0,
    val lastActivity: Long = 0,
)

// ── UI requests ────────────────────────────────────────────────────────────

data class SelectOption(val label: String, val description: String?)

sealed interface CollabUiRequest {
    val reqId: Long
    val kind: String

    data class Select(
        override val reqId: Long,
        val title: String,
        val options: List<SelectOption>,
        val initialIndex: Int,
        val selectionMarker: String?,
        val checkedIndices: List<Int>,
        val markableCount: Int?,
        val helpText: String?,
    ) : CollabUiRequest {
        override val kind: String get() = "select"
    }

    data class Editor(
        override val reqId: Long,
        val title: String,
        val prefill: String?,
    ) : CollabUiRequest {
        override val kind: String get() = "editor"
    }
}

// ── Events ─────────────────────────────────────────────────────────────────

sealed interface AgentEvent {
    data class MessageStart(val message: WireMessage) : AgentEvent
    data class MessageUpdate(val message: WireMessage) : AgentEvent
    data class MessageEnd(val message: WireMessage) : AgentEvent
    data class ToolStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonElement?,
        val intent: String?,
    ) : AgentEvent

    data class ToolUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonElement?,
        val partialResult: JsonElement?,
    ) : AgentEvent

    data class ToolEnd(
        val toolCallId: String,
        val toolName: String,
        val result: JsonElement?,
        val isError: Boolean,
    ) : AgentEvent

    data object AgentStart : AgentEvent
    data object AgentEnd : AgentEvent
    data class Notice(val level: String, val message: String, val source: String?) : AgentEvent
    data class AutoRetryStart(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val errorMessage: String) :
        AgentEvent

    data class AutoRetryEnd(val success: Boolean, val attempt: Int, val finalError: String?) : AgentEvent
    data class AutoCompactionStart(val reason: String, val action: String) : AgentEvent
    data class AutoCompactionEnd(
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String?,
        val skipped: Boolean,
    ) : AgentEvent

    data object TurnStart : AgentEvent
    data object TurnEnd : AgentEvent
    data class ThinkingLevelChanged(val thinkingLevel: String?) : AgentEvent
    data class Unknown(val type: String?) : AgentEvent
}

// ── Frames ─────────────────────────────────────────────────────────────────

sealed interface HostFrame {
    data class Welcome(
        val proto: Int,
        val header: SessionHeader,
        val state: SessionState,
        val agents: List<AgentSnapshot>,
        val entryCount: Int,
        val readOnly: Boolean,
    ) : HostFrame

    data class SnapshotChunk(val entries: List<SessionEntry>, val final: Boolean) : HostFrame
    data class EntryFrame(val entry: SessionEntry) : HostFrame
    data class EventFrame(val event: AgentEvent) : HostFrame
    data class StateFrame(val state: SessionState) : HostFrame
    data class BusFrame(val channel: String, val data: JsonElement?) : HostFrame
    data class AgentsFrame(val agents: List<AgentSnapshot>) : HostFrame
    data class UiRequestFrame(val request: CollabUiRequest) : HostFrame
    data class UiRequestEnd(val reqId: Long) : HostFrame
    data class Bye(val reason: String) : HostFrame
    data class Error(val message: String) : HostFrame
    data class Unknown(val type: String?) : HostFrame
}

sealed interface GuestFrame {
    data class Hello(val name: String, val writeToken: String?) : GuestFrame
    data class Prompt(val text: String) : GuestFrame
    data class UiResponse(val reqId: Long, val value: String?) : GuestFrame
    data object Abort : GuestFrame
}

@Serializable
private data class HelloDto(
    val t: String,
    val proto: Int,
    val name: String,
    val writeToken: String? = null,
)

@Serializable
private data class PromptDto(val t: String, val text: String)

@Serializable
private data class UiResponseDto(val t: String, val reqId: Long, val value: String? = null)

@Serializable
private data class AbortDto(val t: String)

fun encodeGuestFrame(frame: GuestFrame): String = when (frame) {
    is GuestFrame.Hello -> WireJson.encodeToString(
        HelloDto(t = "hello", proto = COLLAB_PROTO, name = frame.name, writeToken = frame.writeToken),
    )

    is GuestFrame.Prompt -> WireJson.encodeToString(PromptDto(t = "prompt", text = frame.text))
    is GuestFrame.UiResponse -> WireJson.encodeToString(
        UiResponseDto(t = "ui-response", reqId = frame.reqId, value = frame.value),
    )

    GuestFrame.Abort -> WireJson.encodeToString(AbortDto(t = "abort"))
}

/**
 * Parses a decrypted host frame. Throws [WireFormatException] for a known
 * frame with malformed required fields; unknown frame/entry types are
 * tolerated and reported as [HostFrame.Unknown] / [UnknownEntry].
 */
fun parseHostFrame(text: String, fallbackEntryId: String = "unknown-entry"): HostFrame {
    val obj = try {
        WireJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        throw WireFormatException("Malformed host frame", e)
    } ?: throw WireFormatException("Host frame is not a JSON object")

    val type = obj.string("t") ?: return HostFrame.Unknown(null)
    return when (type) {
        "welcome" -> {
            val proto = obj.int("proto") ?: throw WireFormatException("welcome.proto missing")
            val header = try {
                WireJson.decodeFromJsonElement(SessionHeader.serializer(), obj.required("header"))
            } catch (e: Exception) {
                throw WireFormatException("welcome.header invalid", e)
            }
            val state = try {
                WireJson.decodeFromJsonElement(SessionState.serializer(), obj.required("state"))
            } catch (e: Exception) {
                throw WireFormatException("welcome.state invalid", e)
            }
            val agents = obj["agents"]?.let { array ->
                try {
                    array.jsonArrayOrThrow().map {
                        WireJson.decodeFromJsonElement(AgentSnapshot.serializer(), it)
                    }
                } catch (e: Exception) {
                    throw WireFormatException("welcome.agents invalid", e)
                }
            } ?: emptyList()
            val entryCount = obj.int("entryCount") ?: throw WireFormatException("welcome.entryCount missing")
            HostFrame.Welcome(proto, header, state, agents, entryCount, obj.boolean("readOnly") == true)
        }

        "snapshot-chunk" -> {
            val entries = obj["entries"]?.jsonArrayOrThrow()
                ?.mapIndexed { index, element -> parseEntry(element, "$fallbackEntryId-$index") }
                ?: emptyList()
            HostFrame.SnapshotChunk(entries, obj.boolean("final") == true)
        }

        "entry" -> HostFrame.EntryFrame(parseEntry(obj.required("entry"), fallbackEntryId))
        "event" -> HostFrame.EventFrame(parseEvent(obj.required("event")))
        "state" -> HostFrame.StateFrame(parseState(obj.required("state")))
        "agents" -> HostFrame.AgentsFrame(
            obj["agents"]?.jsonArrayOrThrow()?.mapNotNull { runCatching { WireJson.decodeFromJsonElement(AgentSnapshot.serializer(), it) }.getOrNull() }
                ?: emptyList(),
        )

        "bus" -> HostFrame.BusFrame(obj.string("channel") ?: "", obj["data"])
        "ui-request" -> HostFrame.UiRequestFrame(parseUiRequest(obj.required("request")))
        "ui-request-end" -> {
            val reqId = obj.long("reqId") ?: throw WireFormatException("ui-request-end.reqId missing")
            HostFrame.UiRequestEnd(reqId)
        }

        "transcript" -> HostFrame.Unknown(type)
        "bye" -> HostFrame.Bye(obj.string("reason") ?: "session ended")
        "error" -> HostFrame.Error(obj.string("message") ?: "host error")
        else -> HostFrame.Unknown(type)
    }
}

private fun parseState(element: JsonElement): SessionState = try {
    WireJson.decodeFromJsonElement(SessionState.serializer(), element)
} catch (e: Exception) {
    throw WireFormatException("state invalid", e)
}

fun parseEntry(element: JsonElement, fallbackId: String): SessionEntry {
    val obj = element as? JsonObject ?: throw WireFormatException("entry is not an object")
    val id = obj.string("id") ?: fallbackId
    val parentId = obj.string("parentId")
    val timestamp = obj.string("timestamp") ?: ""
    return when (val type = obj.string("type")) {
        "message" -> MessageEntry(id, parentId, timestamp, parseMessage(obj.required("message")))
        "custom_message" -> CustomMessageEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            customType = obj.string("customType") ?: "",
            content = obj["content"]?.let { parseMessageContent(it) } ?: MessageContent.Text(""),
            details = obj["details"],
            display = obj.boolean("display") == true,
        )

        "compaction" -> CompactionEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            summary = obj.string("summary") ?: "",
            shortSummary = obj.string("shortSummary"),
            tokensBefore = obj.long("tokensBefore") ?: 0,
        )

        "branch_summary" -> BranchSummaryEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            summary = obj.string("summary") ?: "",
        )

        "model_change" -> ModelChangeEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            model = obj.string("model") ?: "",
        )

        "thinking_level_change" -> ThinkingLevelChangeEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            thinkingLevel = obj.string("thinkingLevel"),
        )

        else -> UnknownEntry(id, parentId, timestamp, type)
    }
}

fun parseMessage(element: JsonElement): WireMessage {
    val obj = element as? JsonObject ?: throw WireFormatException("message is not an object")
    val role = obj.string("role") ?: throw WireFormatException("message.role missing")
    val timestamp = obj.long("timestamp") ?: 0
    return when (role) {
        "user" -> WireMessage.User(
            content = obj["content"]?.let { parseMessageContent(it) } ?: MessageContent.Text(""),
            synthetic = obj.boolean("synthetic") == true,
            timestamp = timestamp,
        )

        "developer" -> WireMessage.Developer(
            content = obj["content"]?.let { parseMessageContent(it) } ?: MessageContent.Text(""),
            timestamp = timestamp,
        )

        "assistant" -> WireMessage.Assistant(
            content = obj["content"]?.jsonArrayOrThrow()?.map { parseContentBlock(it) } ?: emptyList(),
            model = obj.string("model") ?: "",
            usage = obj["usage"]?.let { runCatching { WireJson.decodeFromJsonElement(WireUsage.serializer(), it) }.getOrNull() }
                ?: WireUsage(),
            stopReason = obj.string("stopReason") ?: "",
            errorMessage = obj.string("errorMessage"),
            timestamp = timestamp,
        )

        "toolResult" -> WireMessage.ToolResult(
            toolCallId = obj.string("toolCallId") ?: "",
            toolName = obj.string("toolName") ?: "",
            content = obj["content"]?.jsonArrayOrThrow()?.map { parseContentBlock(it) } ?: emptyList(),
            details = obj["details"],
            isError = obj.boolean("isError") == true,
            timestamp = timestamp,
        )

        else -> throw WireFormatException("unknown message role")
    }
}

private fun parseMessageContent(element: JsonElement): MessageContent = when (element) {
    is JsonPrimitive -> MessageContent.Text(element.contentOrNull ?: "")
    is JsonArray -> MessageContent.Blocks(element.map { parseContentBlock(it) })
    else -> throw WireFormatException("message content invalid")
}

private fun parseContentBlock(element: JsonElement): ContentBlock {
    val obj = element as? JsonObject ?: throw WireFormatException("content block is not an object")
    return when (val type = obj.string("type")) {
        "text" -> ContentBlock.Text(obj.string("text") ?: "")
        "image" -> ContentBlock.Image(obj.string("data") ?: "", obj.string("mimeType") ?: "image/png")
        "thinking" -> ContentBlock.Thinking(obj.string("thinking") ?: "")
        "redactedThinking" -> ContentBlock.RedactedThinking(obj.string("data") ?: "")
        "toolCall" -> ContentBlock.ToolCall(
            id = obj.string("id") ?: "",
            name = obj.string("name") ?: "",
            arguments = obj["arguments"],
            intent = obj.string("intent"),
        )

        else -> throw WireFormatException("unknown content block type", null)
    }
}

private fun parseEvent(element: JsonElement): AgentEvent {
    val obj = element as? JsonObject ?: throw WireFormatException("event is not an object")
    return when (val type = obj.string("type")) {
        "message_start" -> AgentEvent.MessageStart(parseMessage(obj.required("message")))
        "message_update" -> AgentEvent.MessageUpdate(parseMessage(obj.required("message")))
        "message_end" -> AgentEvent.MessageEnd(parseMessage(obj.required("message")))
        "tool_execution_start" -> AgentEvent.ToolStart(
            toolCallId = obj.string("toolCallId") ?: throw WireFormatException("toolCallId missing"),
            toolName = obj.string("toolName") ?: "",
            args = obj["args"],
            intent = obj.string("intent"),
        )

        "tool_execution_update" -> AgentEvent.ToolUpdate(
            toolCallId = obj.string("toolCallId") ?: throw WireFormatException("toolCallId missing"),
            toolName = obj.string("toolName") ?: "",
            args = obj["args"],
            partialResult = obj["partialResult"],
        )

        "tool_execution_end" -> AgentEvent.ToolEnd(
            toolCallId = obj.string("toolCallId") ?: throw WireFormatException("toolCallId missing"),
            toolName = obj.string("toolName") ?: "",
            result = obj["result"],
            isError = obj.boolean("isError") == true,
        )

        "agent_start" -> AgentEvent.AgentStart
        "agent_end" -> AgentEvent.AgentEnd
        "turn_start" -> AgentEvent.TurnStart
        "turn_end" -> AgentEvent.TurnEnd
        "notice" -> AgentEvent.Notice(
            level = obj.string("level") ?: "info",
            message = obj.string("message") ?: "",
            source = obj.string("source"),
        )

        "auto_retry_start" -> AgentEvent.AutoRetryStart(
            attempt = obj.int("attempt") ?: 0,
            maxAttempts = obj.int("maxAttempts") ?: 0,
            delayMs = obj.long("delayMs") ?: 0,
            errorMessage = obj.string("errorMessage") ?: "",
        )

        "auto_retry_end" -> AgentEvent.AutoRetryEnd(
            success = obj.boolean("success") == true,
            attempt = obj.int("attempt") ?: 0,
            finalError = obj.string("finalError"),
        )

        "auto_compaction_start" -> AgentEvent.AutoCompactionStart(
            reason = obj.string("reason") ?: "",
            action = obj.string("action") ?: "",
        )

        "auto_compaction_end" -> AgentEvent.AutoCompactionEnd(
            aborted = obj.boolean("aborted") == true,
            willRetry = obj.boolean("willRetry") == true,
            errorMessage = obj.string("errorMessage"),
            skipped = obj.boolean("skipped") == true,
        )

        "thinking_level_changed" -> AgentEvent.ThinkingLevelChanged(obj.string("thinkingLevel"))
        else -> AgentEvent.Unknown(type)
    }
}

private fun parseUiRequest(element: JsonElement): CollabUiRequest {
    val obj = element as? JsonObject ?: throw WireFormatException("ui request is not an object")
    val reqId = obj.long("reqId") ?: throw WireFormatException("ui request reqId missing")
    return when (val kind = obj.string("kind")) {
        "select" -> CollabUiRequest.Select(
            reqId = reqId,
            title = obj.string("title") ?: "",
            options = obj["options"]?.jsonArrayOrThrow()?.mapNotNull { option ->
                when (option) {
                    is JsonPrimitive -> option.contentOrNull?.let { SelectOption(it, null) }
                    is JsonObject -> option.string("label")?.let { SelectOption(it, option.string("description")) }
                    else -> null
                }
            } ?: emptyList(),
            initialIndex = obj.int("initialIndex") ?: 0,
            selectionMarker = obj.string("selectionMarker"),
            checkedIndices = obj["checkedIndices"]?.jsonArrayOrThrow()?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
            markableCount = obj.int("markableCount"),
            helpText = obj.string("helpText"),
        )

        "editor" -> CollabUiRequest.Editor(reqId, obj.string("title") ?: "", obj.string("prefill"))
        else -> throw WireFormatException("unknown ui request kind: $kind")
    }
}

class WireFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.required(key: String): JsonElement =
    this[key] ?: throw WireFormatException("$key missing")

private fun JsonElement.jsonArrayOrThrow(): JsonArray =
    this as? JsonArray ?: throw WireFormatException("expected a JSON array")
