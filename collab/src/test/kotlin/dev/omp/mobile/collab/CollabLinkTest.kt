package dev.omp.mobile.collab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabLinkTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = encodeBase64Url(key)
    private val token = ByteArray(16) { (0xf0 + it).toByte() }
    private val tokenText = encodeBase64Url(token)
    private val room = "AbCdEf123456_-Xy"

    private fun valid(raw: String): PairingCredentials {
        val parsed = parseCollabLink(raw)
        assertTrue("expected valid: $raw -> $parsed", parsed is PairingParseResult.Valid)
        return (parsed as PairingParseResult.Valid).credentials
    }

    private fun invalid(raw: String, code: PairingErrorCode? = null) {
        val parsed = parseCollabLink(raw)
        assertTrue("expected invalid: $raw -> $parsed", parsed is PairingParseResult.Invalid)
        if (code != null) assertEquals(code, (parsed as PairingParseResult.Invalid).error.code)
    }

    @Test
    fun publicControlLinkFromPlan() {
        val link =
            "https://my.omp.sh/#AbCdEf123456_-Xy.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh_w8fLz9PX29_j5-vv8_f7_"
        val credentials = valid(link)
        assertEquals("wss://my.omp.sh/r/AbCdEf123456_-Xy?role=guest", credentials.wsUrl)
        assertEquals(room, credentials.roomId)
        assertArrayEquals(ByteArray(32) { it.toByte() }, credentials.key)
        assertArrayEquals(token, credentials.writeToken)
        assertEquals("8PHy8_T19vf4-fr7_P3-_w", encodeBase64Url(credentials.writeToken!!))
        assertFalse(credentials.toString().contains(encodeBase64Url(credentials.key)))
        assertFalse(credentials.toString().contains(tokenText))
    }

    @Test
    fun viewSecretHasNoWriteToken() {
        val credentials = valid("https://my.omp.sh/#$room.$keyText")
        assertEquals(32, credentials.key.size)
        assertEquals(null, credentials.writeToken)
        assertFalse(credentials.hasWriteToken)
    }

    @Test
    fun bareAndLegacyBareUseDefaultRelay() {
        val dot = valid("$room.$keyText")
        assertEquals("$DEFAULT_RELAY_URL/r/$room?role=guest", dot.wsUrl)
        assertArrayEquals(key, dot.key)

        val legacy = valid("$room#$keyText")
        assertEquals("$DEFAULT_RELAY_URL/r/$room?role=guest", legacy.wsUrl)
        assertArrayEquals(key, legacy.key)
    }

    @Test
    fun schemeLessRelayInfersWss() {
        val credentials = valid("relay.example.com:8443/r/$room#$keyText")
        assertEquals("wss://relay.example.com:8443/r/$room?role=guest", credentials.wsUrl)
    }

    @Test
    fun plainWsOnlyForLocalhost() {
        val local = valid("ws://localhost:7466/r/$room#$keyText")
        assertEquals("ws://localhost:7466/r/$room?role=guest", local.wsUrl)
        invalid("ws://relay.example.com/r/$room#$keyText", PairingErrorCode.INSECURE_RELAY)
        invalid("http://relay.example.com/r/$room#$keyText", PairingErrorCode.INSECURE_RELAY)
    }

    @Test
    fun invalidSecretSizesAreRejected() {
        invalid("$room#${encodeBase64Url(ByteArray(16))}", PairingErrorCode.INVALID_KEY)
        invalid("$room#${encodeBase64Url(ByteArray(40))}", PairingErrorCode.INVALID_KEY)
    }

    @Test
    fun invalidAlphabetAndMissingKeyAreRejected() {
        invalid("https://relay.example.com/r/$room#$keyText!", PairingErrorCode.INVALID_KEY)
        invalid("wss://relay.example.com/r/$room", PairingErrorCode.MISSING_KEY)
        invalid("wss://relay.example.com/r/$room/", PairingErrorCode.MISSING_PATH)
        invalid("wss://relay.example.com/r/$room.$keyText/", PairingErrorCode.MISSING_PATH)
    }

    @Test
    fun webDeepLinks() {
        val bare = valid("https://my.omp.sh/#$room#$keyText")
        assertEquals("$DEFAULT_RELAY_URL/r/$room?role=guest", bare.wsUrl)
        assertArrayEquals(key, bare.key)

        val custom = valid("https://relay.example.com:8443/#relay.example.com:8443/r/$room#$keyText")
        assertEquals("wss://relay.example.com:8443/r/$room?role=guest", custom.wsUrl)

        val local = valid("http://localhost:7466/#ws://localhost:7466/r/$room#$keyText")
        assertEquals("ws://localhost:7466/r/$room?role=guest", local.wsUrl)
    }

    @Test
    fun wrapperFragmentsWinOverOuterPaths() {
        val parsed = valid("https://web.example/r/abcdefghij#wss://relay.example.com/r/$room.$keyText")
        assertEquals("wss://relay.example.com/r/$room?role=guest", parsed.wsUrl)

        invalid("https://web.example/#not-a-collab-link", PairingErrorCode.MISSING_PATH)
    }

    @Test
    fun directLegacyLinksAndPercentEncodedHash() {
        val legacy = valid("https://relay.example.com/r/$room#$keyText")
        assertEquals("wss://relay.example.com/r/$room?role=guest", legacy.wsUrl)

        val mangled = valid("https://my.omp.sh/#$room%23$keyText")
        assertEquals("$DEFAULT_RELAY_URL/r/$room?role=guest", mangled.wsUrl)
    }

    @Test
    fun dotJoinedWebDeepLink() {
        val parsed = valid("https://my.omp.sh/#$room.$keyText")
        assertEquals("$DEFAULT_RELAY_URL/r/$room?role=guest", parsed.wsUrl)
        assertArrayEquals(key, parsed.key)
    }

    @Test
    fun ipv6AndCustomPortsAreCanonicalized() {
        val parsed = valid("wss://[::1]:7466/r/$room#$keyText")
        assertEquals("wss://[::1]:7466/r/$room?role=guest", parsed.wsUrl)

        val local = valid("ws://[::1]:7466/r/$room#$keyText")
        assertEquals("ws://[::1]:7466/r/$room?role=guest", local.wsUrl)
    }

    @Test
    fun formatRoundTripsForRepositoryPairing() {
        for (credentials in listOf(
            PairingCredentials(DEFAULT_RELAY_URL, room, key, token),
            PairingCredentials("wss://relay.example.com:8443", room, key, token),
            PairingCredentials("ws://127.0.0.1:7466", room, key, null),
        )) {
            val parsed = parseCollabLink(formatCollabLink(credentials))
            assertTrue(parsed is PairingParseResult.Valid)
            assertTrue((parsed as PairingParseResult.Valid).credentials == credentials)
        }
    }

    @Test
    fun errorMessagesNeverContainTheSecret() {
        val secret = encodeBase64Url(ByteArray(32) { 7 })
        val parsed = parseCollabLink("https://relay.example.com/r/$room#${secret}!")
        assertTrue(parsed is PairingParseResult.Invalid)
        val text = (parsed as PairingParseResult.Invalid).error.toString()
        assertFalse(text.contains(secret))
    }

    @Test
    fun envelopeRoundTripAndGuestPrefix() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val envelope = packEnvelope(7, payload)
        val unpacked = unpackEnvelope(envelope)!!
        assertEquals(7, unpacked.peerId)
        assertArrayEquals(payload, unpacked.payload)

        val guest = packEnvelope(0, payload)
        assertEquals(0, unpackEnvelope(guest)!!.peerId)
        assertEquals(0x01020304, unpackEnvelope(byteArrayOf(1, 2, 3, 4))!!.peerId)
        assertEquals(null, unpackEnvelope(byteArrayOf(0, 0)))
    }
}
