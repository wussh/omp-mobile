package dev.omp.mobile.collab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Custom message type the host uses for batched advisor notes (`session-advisors.ts`). */
const val ADVISOR_MESSAGE_TYPE = "advisor"

/**
 * Severity levels the host's advisor tool emits (`AdvisorNote.severity`).
 * Unknown severity values are kept as raw text instead of being coerced.
 */
enum class AdvisorSeverity(val label: String) {
    Nit("Nit"),
    Concern("Concern"),
    Blocker("Blocker");

    companion object {
        fun from(raw: String?): AdvisorSeverity? = when (raw?.trim()?.lowercase()) {
            "nit" -> Nit
            "concern" -> Concern
            "blocker" -> Blocker
            else -> null
        }
    }
}

/**
 * One rendered advisor note.
 *
 * [severityName] carries a host severity this build does not know; it must be
 * shown verbatim rather than mapped onto one of the known levels.
 */
data class AdvisorNoteView(
    val text: String,
    val severity: AdvisorSeverity? = null,
    val severityName: String? = null,
    val advisor: String? = null,
    val turnsAgo: Int? = null,
) {
    val severityLabel: String? get() = severity?.label ?: severityName
}

/** Presentation model for one `customType = "advisor"` custom message. */
data class AdvisorCardView(
    val notes: List<AdvisorNoteView>,
    /** True when the notes were recovered from the wrapped XML-style message body. */
    val fromContent: Boolean = false,
) {
    val blockerCount: Int get() = notes.count { it.severity == AdvisorSeverity.Blocker }
}

/**
 * Canonical host shape, from can1357/oh-my-pi `session-advisors.ts` /
 * `advisor/advise-tool.ts`:
 *
 * - `details = { notes: [{ note, severity?, advisor?, turn?, turnsAgo? }] }`
 * - `content = '<advisory advisor="…" severity="…" guidance="…">\n<escaped note>\n</advisory>'`
 *   blocks joined by newlines.
 *
 * Structured `details.notes` win whenever they contain readable text. Malformed
 * or missing details fall back to unwrapping only the known `<advisory>` /
 * `</advisory>` container lines in the content, so legacy batches stay readable.
 */
fun advisorCard(details: JsonElement?, content: MessageContent): AdvisorCardView {
    val structured = notesFromDetails(details)
    if (structured != null && structured.complete && structured.notes.isNotEmpty()) {
        return AdvisorCardView(notes = structured.notes, fromContent = false)
    }
    val fromContent = notesFromContent(content)
    if (fromContent.isNotEmpty()) {
        return AdvisorCardView(notes = fromContent, fromContent = true)
    }
    // Details were partially readable but the body was absent: keep what we could read.
    return AdvisorCardView(
        notes = structured?.notes.orEmpty(),
        fromContent = structured == null,
    )
}

/** Text of a custom message body; non-text blocks are not advice and are skipped. */
fun messageContentText(content: MessageContent): String = when (content) {
    is MessageContent.Text -> content.text
    is MessageContent.Blocks -> content.blocks
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n\n") { it.text }
}

private data class StructuredNotes(
    val notes: List<AdvisorNoteView>,
    val complete: Boolean,
)

private fun notesFromDetails(details: JsonElement?): StructuredNotes? {
    val notes = (details as? JsonObject)?.get("notes") as? JsonArray ?: return null
    if (notes.isEmpty()) return StructuredNotes(emptyList(), complete = false)
    val parsed = ArrayList<AdvisorNoteView>(notes.size)
    var complete = true
    for (element in notes) {
        val obj = element as? JsonObject
        if (obj == null) {
            complete = false
            continue
        }
        val note = (obj["note"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (note == null) {
            complete = false
            continue
        }
        val rawSeverity = obj.stringOrNull("severity")?.trim()?.takeIf { it.isNotEmpty() }
        parsed += AdvisorNoteView(
            text = note,
            severity = AdvisorSeverity.from(rawSeverity),
            severityName = rawSeverity?.takeIf { AdvisorSeverity.from(it) == null },
            advisor = obj.stringOrNull("advisor")?.trim()?.takeIf { it.isNotEmpty() },
            turnsAgo = obj.stringOrNull("turnsAgo")?.trim()?.toIntOrNull(),
        )
    }
    return StructuredNotes(notes = parsed, complete = complete)
}

private fun notesFromContent(content: MessageContent): List<AdvisorNoteView> {
    val text = messageContentText(content).replace("\r\n", "\n")
    if (text.isBlank()) return emptyList()
    return unwrapAdvisories(text) ?: listOf(AdvisorNoteView(text = text.trim()))
}

private data class OpenAdvisory(
    val attributes: Map<String, String>,
    val lines: MutableList<String> = mutableListOf(),
)

/**
 * Removes only host-generated `<advisory …>` / `</advisory>` container lines.
 * Fenced code is tracked so an advisory-looking line inside a code fence is
 * preserved verbatim. Any other angle-bracket text is left untouched.
 */
private fun unwrapAdvisories(text: String): List<AdvisorNoteView>? {
    val notes = mutableListOf<AdvisorNoteView>()
    val loose = mutableListOf<String>()
    var open: OpenAdvisory? = null
    var foundWrapper = false
    var fenceChar: Char? = null
    var fenceLength = 0

    fun append(line: String) {
        val current = open
        if (current != null) current.lines.add(line) else loose.add(line)
    }

    fun flushLoose() {
        if (open == null && loose.any { it.isNotBlank() }) {
            notes += AdvisorNoteView(text = loose.joinToString("\n").trim())
            loose.clear()
        }
    }

    fun closeOpen() {
        val current = open ?: return
        val body = current.lines.joinToString("\n").trim()
        if (body.isNotEmpty()) {
            val rawSeverity = current.attributes["severity"]?.trim()?.takeIf { it.isNotEmpty() }
            notes += AdvisorNoteView(
                text = decodeXmlText(body),
                severity = AdvisorSeverity.from(rawSeverity),
                severityName = rawSeverity?.takeIf { AdvisorSeverity.from(it) == null },
                advisor = current.attributes["advisor"]?.trim()?.takeIf { it.isNotEmpty() },
                turnsAgo = current.attributes["turns_ago"]?.trim()?.toIntOrNull(),
            )
        }
        open = null
    }

    for (line in text.split('\n')) {
        val trimmed = line.trim()
        if (fenceChar != null) {
            if (isFenceClose(trimmed, fenceChar!!, fenceLength)) fenceChar = null
            append(line)
            continue
        }
        val fence = fenceOpen(trimmed)
        if (fence != null) {
            fenceChar = fence.first
            fenceLength = fence.second
            append(line)
            continue
        }
        val attributes = advisoryOpen(trimmed)
        if (attributes != null) {
            foundWrapper = true
            if (open != null) closeOpen() else flushLoose()
            open = OpenAdvisory(attributes)
            continue
        }
        if (trimmed == "</advisory>" && open != null) {
            closeOpen()
            continue
        }
        append(line)
    }
    closeOpen()
    flushLoose()

    return if (foundWrapper) notes else null
}

private fun advisoryOpen(trimmed: String): Map<String, String>? {
    if (!trimmed.startsWith("<advisory")) return null
    if (!trimmed.endsWith(">")) return null
    val rest = trimmed.removePrefix("<advisory")
    val body = rest.removeSuffix(">")
    if (body.isNotEmpty() && !body.first().isWhitespace()) return null
    val attributes = mutableMapOf<String, String>()
    for (match in AttributePattern.findAll(body)) {
        attributes[match.groupValues[1]] = decodeXmlText(match.groupValues[2])
    }
    return attributes
}

private val AttributePattern = Regex("""([A-Za-z_][\w:.-]*)\s*=\s*"([^"]*)"""")

/** Fence character and opening run length, or null when the line is not a fence opener. */
private fun fenceOpen(trimmed: String): Pair<Char, Int>? {
    val char = trimmed.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val length = trimmed.takeWhile { it == char }.length
    return if (length >= 3) char to length else null
}

private fun isFenceClose(trimmed: String, char: Char, minLength: Int): Boolean {
    val length = trimmed.takeWhile { it == char }.length
    return length >= minLength && trimmed.drop(length).isBlank()
}

private fun decodeXmlText(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
