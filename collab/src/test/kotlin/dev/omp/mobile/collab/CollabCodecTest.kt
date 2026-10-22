package dev.omp.mobile.collab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class CollabCodecTest {
    private val vectorKey = "AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK-2vcTL0tk"
    private val vectorSealed =
        "m0PA1QNfpOGtl_iq1yfKhoux0moFN_WQtCExumBVOWKeHFY_yx7T4s3B5YFUSn6Dc9aAyVsjIjPQXLxqsg8_UQiZ9Q"

    @Test
    fun decryptsCodingAgentInteropVector() {
        val key = decodeBase64Url(vectorKey)!!
        val sealed = decodeBase64Url(vectorSealed)!!
        val cipher = RoomCipher.fromKey(key)
        assertEquals("""{"t":"hello","proto":1,"name":"vector"}""", cipher.open(sealed))
    }

    @Test
    fun reEncryptsGoldenBytesWithGoldenIv() {
        val key = decodeBase64Url(vectorKey)!!
        val sealed = decodeBase64Url(vectorSealed)!!
        val iv = sealed.copyOfRange(0, RoomCipher.IV_LENGTH)
        val cipher = RoomCipher.fromKey(key)
        val reSealed = cipher.seal("""{"t":"hello","proto":1,"name":"vector"}""", iv)
        assertArrayEquals(sealed, reSealed)
    }

    @Test
    fun roundTripsRandomIv() {
        val cipher = RoomCipher.fromKey(decodeBase64Url(vectorKey)!!)
        val text = """{"t":"prompt","text":"hello there"}"""
        val sealed = cipher.seal(text)
        assertEquals(text, cipher.open(sealed))
        assertEquals(RoomCipher.IV_LENGTH + text.toByteArray().size + 16, sealed.size)
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val cipher = RoomCipher.fromKey(decodeBase64Url(vectorKey)!!)
        val sealed = cipher.seal("""{"t":"abort"}""")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0xff).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed) }
    }

    @Test
    fun rejectsWrongKey() {
        val wrong = RoomCipher.fromKey(ByteArray(32) { 9 })
        val sealed = RoomCipher.fromKey(decodeBase64Url(vectorKey)!!).seal("""{"t":"abort"}""")
        assertThrows(GeneralSecurityException::class.java) { wrong.open(sealed) }
    }

    @Test
    fun rejectsTruncatedFrame() {
        val cipher = RoomCipher.fromKey(decodeBase64Url(vectorKey)!!)
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(12)) }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(0)) }
    }

    @Test
    fun helloFrameOmitsMissingTokenAndKeepsProto() {
        val text = encodeGuestFrame(GuestFrame.Hello("Android", null))
        assertEquals("""{"t":"hello","proto":3,"name":"Android"}""", text)

        val control = encodeGuestFrame(GuestFrame.Hello("Android", "abc"))
        assertEquals("""{"t":"hello","proto":3,"name":"Android","writeToken":"abc"}""", control)
    }

    @Test
    fun promptImageToStringRedactsInlineData() {
        val text = PromptImage("c2VjcmV0LWJ5dGVz", "image/png").toString()
        assertEquals("PromptImage(data=<redacted>, mimeType=image/png)", text)
    }

    @Test
    fun promptWithImagesUsesWireImageContent() {
        val text = encodeGuestFrame(
            GuestFrame.Prompt(
                text = "",
                images = listOf(PromptImage("YWJj", "image/jpeg")),
            ),
        )
        assertEquals(
            """{"t":"prompt","text":"","images":[{"type":"image","data":"YWJj","mimeType":"image/jpeg"}]}""",
            text,
        )
    }

    @Test
    fun cancelResponseOmitsNullValue() {
        assertEquals("""{"t":"ui-response","reqId":7}""", encodeGuestFrame(GuestFrame.UiResponse(7, null)))
        assertEquals(
            """{"t":"ui-response","reqId":7,"value":"Option A"}""",
            encodeGuestFrame(GuestFrame.UiResponse(7, "Option A")),
        )
    }

    @Test
    fun agentCommandsUseTypedWireNamesAndOmitUnusedText() {
        assertEquals(
            """{"t":"agent-cmd","cmd":"chat","agentId":"a1","text":"hello"}""",
            encodeGuestFrame(GuestFrame.AgentCmd(AgentCommand.CHAT, "a1", "hello")),
        )
        assertEquals(
            """{"t":"agent-cmd","cmd":"kill","agentId":"a1"}""",
            encodeGuestFrame(GuestFrame.AgentCmd(AgentCommand.KILL, "a1", null)),
        )
        assertEquals(
            """{"t":"agent-cmd","cmd":"revive","agentId":"a1"}""",
            encodeGuestFrame(GuestFrame.AgentCmd(AgentCommand.REVIVE, "a1", "ignored")),
        )
    }

    @Test
    fun fetchTranscriptCarriesRequestCursor() {
        assertEquals(
            """{"t":"fetch-transcript","reqId":3,"agentId":"a1","fromByte":128}""",
            encodeGuestFrame(GuestFrame.FetchTranscript(3, "a1", 128)),
        )
    }

    @Test
    fun transcriptResponseParsesRowsAndHostError() {
        val rows = parseHostFrame("""{"t":"transcript","reqId":9,"text":"{\"a\":1}\n","newSize":256}""")
        assertEquals(HostFrame.Transcript(9, "{\"a\":1}\n", 256, null), rows)

        val error = parseHostFrame("""{"t":"transcript","reqId":10,"text":"","newSize":0,"error":"missing file"}""")
        assertEquals(HostFrame.Transcript(10, "", 0, "missing file"), error)
    }

    @Test
    fun malformedTranscriptResponseIsAFormatError() {
        assertThrows(WireFormatException::class.java) {
            parseHostFrame("""{"t":"transcript","reqId":1,"text":"x"}""")
        }
    }
}
