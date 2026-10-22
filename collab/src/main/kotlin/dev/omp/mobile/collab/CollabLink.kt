package dev.omp.mobile.collab

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Default public relay used by bare and scheme-less collab links. */
const val DEFAULT_RELAY_URL = "wss://my.omp.sh"

const val ROOM_KEY_BYTES = 32
const val WRITE_TOKEN_BYTES = 16
const val ENVELOPE_HEADER_LENGTH = 4
const val COLLAB_PROTO = 3

private val ROOM_PATH_RE = Regex("^/r/([A-Za-z0-9_-]{10,64})(?:\\.([A-Za-z0-9_-]+))?$")
private val BARE_LINK_RE = Regex("^([A-Za-z0-9_-]{10,64})[#.]([A-Za-z0-9_-]+)$")
private val B64URL_RE = Regex("^[A-Za-z0-9_-]+$")
private val LOCAL_HOSTNAMES = setOf("localhost", "127.0.0.1", "::1", "[::1]")

enum class PairingErrorCode {
    INVALID_LINK,
    UNSUPPORTED_SCHEME,
    INSECURE_RELAY,
    MISSING_PATH,
    MISSING_KEY,
    INVALID_KEY,
}

/** Never contains the raw scanned text. */
data class PairingError(val code: PairingErrorCode, val message: String)

sealed interface PairingParseResult {
    data class Valid(val credentials: PairingCredentials) : PairingParseResult
    data class Invalid(val error: PairingError) : PairingParseResult
}

/**
 * Parsed full (Control) or bare (View) collab link.
 *
 * The string representation is redacted: callers may log the object without
 * leaking the room key or write token.
 */
class PairingCredentials(
    val relayOrigin: String,
    val roomId: String,
    val key: ByteArray,
    val writeToken: ByteArray?,
) {
    /** Connection endpoint; never carries a token in the URL. */
    val wsUrl: String
        get() = "$relayOrigin/r/$roomId?role=guest"

    val hasWriteToken: Boolean
        get() = writeToken != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingCredentials) return false
        return relayOrigin == other.relayOrigin &&
            roomId == other.roomId &&
            key.contentEquals(other.key) &&
            writeToken.contentEquals(other.writeToken)
    }

    override fun hashCode(): Int {
        var result = relayOrigin.hashCode()
        result = 31 * result + roomId.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + (writeToken?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PairingCredentials(relayOrigin=$relayOrigin, roomId=$roomId, key=<redacted>, hasWriteToken=$hasWriteToken)"
}

fun encodeBase64Url(bytes: ByteArray): String {
    val plain = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    return plain
}

/** Strict base64url; null on an invalid alphabet, padding, or malformed length. */
fun decodeBase64Url(text: String): ByteArray? {
    if (!B64URL_RE.matches(text)) return null
    return try {
        java.util.Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Renders a minimal shareable link for a parsed credential (no UI display). */
fun formatCollabLink(credentials: PairingCredentials): String {
    val secret = if (credentials.writeToken != null) {
        credentials.key + credentials.writeToken
    } else {
        credentials.key
    }
    val secretText = encodeBase64Url(secret)
    val origin = credentials.relayOrigin
    if (origin == DEFAULT_RELAY_URL) return "${credentials.roomId}.$secretText"
    val compact = origin.removePrefix("wss://")
    return "$compact/r/${credentials.roomId}.$secretText"
}

/**
 * Port of the upstream `parseCollabLink` (collab-web/src/lib/link.ts).
 *
 * Accepts bare `<room>.<key>`, legacy `<room>#<key>`, direct relay paths, and
 * web wrappers whose fragment carries a complete collab link.
 */
fun parseCollabLink(raw: String): PairingParseResult = parseCollabLink(raw, depth = 0)

private fun parseCollabLink(raw: String, depth: Int): PairingParseResult {
    if (depth > 4) return invalid(PairingErrorCode.INVALID_LINK, "Collab link is nested too deeply")

    var text = raw.trim().replace(Regex("%23", RegexOption.IGNORE_CASE), "#")
    if (text.isEmpty()) return invalid(PairingErrorCode.INVALID_LINK, "Collab link is empty")

    val bare = BARE_LINK_RE.matchEntire(text)
    if (bare != null) {
        text = "$DEFAULT_RELAY_URL/r/${bare.groupValues[1]}.${bare.groupValues[2]}"
    } else if (!text.contains("://")) {
        text = "wss://$text"
    }

    val rawScheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
    val parseScheme = when (rawScheme) {
        "ws", "http" -> "http"
        "wss", "https" -> "https"
        else -> return invalid(PairingErrorCode.UNSUPPORTED_SCHEME, "Unsupported relay URL scheme")
    }
    val parseable = parseScheme + text.substring(rawScheme.length)

    val url = parseable.toHttpUrlOrNull()
        ?: return invalid(PairingErrorCode.INVALID_LINK, "Invalid collab link")

    val isHttpWrapper = rawScheme == "http" || rawScheme == "https"
    val fragment = url.encodedFragment ?: ""
    if (isHttpWrapper && fragment.isNotEmpty()) {
        val inner = parseCollabLink(fragment, depth + 1)
        if (inner is PairingParseResult.Valid) return inner
    }

    val transportScheme = when (rawScheme) {
        "ws", "http" -> "ws"
        else -> "wss"
    }
    if (transportScheme == "ws" && url.host !in LOCAL_HOSTNAMES) {
        return invalid(
            PairingErrorCode.INSECURE_RELAY,
            "Relay must use wss://; plain ws:// is only allowed for localhost",
        )
    }

    val match = ROOM_PATH_RE.matchEntire(url.encodedPath)
    if (match == null) {
        if (!isHttpWrapper && fragment.isNotEmpty()) return parseCollabLink(fragment, depth + 1)
        return invalid(PairingErrorCode.MISSING_PATH, "Collab link must contain a /r/<roomId> path")
    }

    val roomId = match.groupValues[1]
    val pathSecret = match.groupValues[2]
    val secretText = pathSecret.ifEmpty { fragment }
    if (secretText.isEmpty()) {
        return invalid(PairingErrorCode.MISSING_KEY, "Collab link is missing the key part")
    }
    val secret = decodeBase64Url(secretText)
        ?: return invalid(PairingErrorCode.INVALID_KEY, "Collab link key is not valid base64url")
    if (secret.size != ROOM_KEY_BYTES && secret.size != ROOM_KEY_BYTES + WRITE_TOKEN_BYTES) {
        return invalid(
            PairingErrorCode.INVALID_KEY,
            "Collab link key must be 32 (view) or 48 (full) bytes",
        )
    }

    val key = secret.copyOfRange(0, ROOM_KEY_BYTES)
    val token = if (secret.size > ROOM_KEY_BYTES) secret.copyOfRange(ROOM_KEY_BYTES, secret.size) else null
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val defaultPort = if (transportScheme == "wss") 443 else 80
    val port = if (url.port == defaultPort) "" else ":${url.port}"
    val origin = "$transportScheme://$host$port"
    return PairingParseResult.Valid(PairingCredentials(origin, roomId, key, token))
}

private fun invalid(code: PairingErrorCode, message: String): PairingParseResult =
    PairingParseResult.Invalid(PairingError(code, message))
