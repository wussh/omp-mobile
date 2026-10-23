package dev.omp.mobile.collab

const val ASYNC_RESULT_MESSAGE_TYPE = "async-result"

/**
 * The host's async-result.md wraps job output in a model-facing system notice.
 * Remove only that complete outer envelope. Nested task metadata, literal tags,
 * code and indentation remain output, never HTML to execute or hide.
 */
fun asyncResultText(text: String): String {
    val normalized = text.replace("\r\n", "\n")
    val start = "<system-notice>\n"
    val end = "\n</system-notice>"
    return if (normalized.startsWith(start) && normalized.endsWith(end)) {
        normalized.substring(start.length, (normalized.length - end.length).coerceAtLeast(start.length))
    } else {
        normalized
    }
}
