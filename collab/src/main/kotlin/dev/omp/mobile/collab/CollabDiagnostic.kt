package dev.omp.mobile.collab

/** Closed metadata vocabulary: never accept URLs, credentials, payloads, or exception messages. */
enum class DiagnosticEvent {
    CONNECT_ATTEMPT, SOCKET_OPEN, HELLO_SENT, WELCOME, SNAPSHOT_CHUNK, READY,
    PARSE_FAILED, DECRYPT_FAILED, SOCKET_CLOSED, TRANSPORT_FAILED, RETRY_SCHEDULED, SEND_REJECTED,
    WELCOME_TIMEOUT, SNAPSHOT_TIMEOUT,
}

enum class DiagnosticFrame {
    WELCOME, SNAPSHOT_CHUNK, ENTRY, EVENT, STATE, AGENTS, BUS, UI_REQUEST, UI_REQUEST_END,
    TRANSCRIPT, BYE, ERROR, UNKNOWN,
}

enum class WireIssue {
    JSON, FRAME_OBJECT, WELCOME_PROTOCOL, WELCOME_HEADER, WELCOME_STATE, WELCOME_AGENTS,
    WELCOME_ENTRY_COUNT, MESSAGE_OBJECT, MESSAGE_ROLE, MESSAGE_CONTENT, CONTENT_BLOCK,
    CONTENT_TYPE, EVENT_OBJECT, UI_REQUEST, FRAME_STRUCTURE,
}

enum class TransportIssue { DNS, TLS, TIMEOUT, IO, OTHER }

/** Only numeric counters and enums can cross into Android logging. No session text is retained. */
data class CollabDiagnostic(
    val event: DiagnosticEvent,
    val activation: Long,
    val attempt: Long,
    val welcomed: Boolean,
    val frame: DiagnosticFrame? = null,
    val bytes: Int? = null,
    val entries: Int? = null,
    val total: Int? = null,
    val closeCode: Int? = null,
    val retryMs: Long? = null,
    val issue: WireIssue? = null,
    val transportIssue: TransportIssue? = null,
    val protocol: Int? = null,
)

internal fun diagnosticFrame(type: String?): DiagnosticFrame = when (type) {
    "welcome" -> DiagnosticFrame.WELCOME
    "snapshot-chunk" -> DiagnosticFrame.SNAPSHOT_CHUNK
    "entry" -> DiagnosticFrame.ENTRY
    "event" -> DiagnosticFrame.EVENT
    "state" -> DiagnosticFrame.STATE
    "agents" -> DiagnosticFrame.AGENTS
    "bus" -> DiagnosticFrame.BUS
    "ui-request" -> DiagnosticFrame.UI_REQUEST
    "ui-request-end" -> DiagnosticFrame.UI_REQUEST_END
    "transcript" -> DiagnosticFrame.TRANSCRIPT
    "bye" -> DiagnosticFrame.BYE
    "error" -> DiagnosticFrame.ERROR
    else -> DiagnosticFrame.UNKNOWN
}
