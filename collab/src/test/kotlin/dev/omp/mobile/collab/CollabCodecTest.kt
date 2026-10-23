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

    @Test
    fun providerReplayBlocksDoNotDiscardRenderableAssistantContent() {
        // Rich host messages include provider-only blocks outside the rendered wire subset.
        val frame = parseHostFrame(
            """{"t":"event","event":{"type":"message_update","message":{
                "role":"assistant","timestamp":7,"model":"m","usage":{},"stopReason":"toolUse",
                "content":[
                    {"type":"text","text":"Searching"},
                    {"type":"fallback","from":{"model":"first"},"to":{"model":"second"}},
                    {"type":"anthropicServerTool","block":{"type":"server_tool_use","id":"native-search","name":"web_search"}},
                    {"type":"toolCall","id":"call-1","name":"read","arguments":{"path":"file.txt"}},
                    {"type":"text","text":"Finished"}
                ]
            }}}""",
        ) as HostFrame.EventFrame
        val message = (frame.event as AgentEvent.MessageUpdate).message as WireMessage.Assistant
        assertEquals(7L, message.timestamp)
        assertEquals(3, message.content.size)
        assertEquals(ContentBlock.Text("Searching"), message.content[0])
        val call = message.content[1] as ContentBlock.ToolCall
        assertEquals("call-1", call.id)
        assertEquals("read", call.name)
        assertEquals(WireJson.parseToJsonElement("""{"path":"file.txt"}"""), call.arguments)
        assertEquals(ContentBlock.Text("Finished"), message.content[2])
    }

    @Test
    fun unsupportedBlocksDoNotDiscardKnownUserOrToolResultContent() {
        val frame = parseHostFrame(
            """{"t":"snapshot-chunk","final":true,"entries":[
                {"type":"message","id":"u","message":{"role":"user","timestamp":1,"content":[
                    {"type":"text","text":"Keep user text"},{"type":"futureBlock","text":"not renderable"},
                    {"type":"image","data":"YWJj","mimeType":"image/png"}
                ]}},
                {"type":"message","id":"r","message":{"role":"toolResult","toolCallId":"c","toolName":"read","timestamp":2,"content":[
                    {"type":"futureBlock"},{"type":"text","text":"Keep result"}
                ]}}
            ]}""",
        ) as HostFrame.SnapshotChunk
        val user = (frame.entries[0] as MessageEntry).message as WireMessage.User
        assertEquals(
            MessageContent.Blocks(listOf(ContentBlock.Text("Keep user text"), ContentBlock.Image("YWJj", "image/png"))),
            user.content,
        )
        val result = (frame.entries[1] as MessageEntry).message as WireMessage.ToolResult
        assertEquals("c", result.toolCallId)
        assertEquals(listOf(ContentBlock.Text("Keep result")), result.content)
    }

    @Test
    fun malformedMessagesAndBlocksRemainProtocolErrors() {
        assertThrows(WireFormatException::class.java) {
            parseHostFrame("""{"t":"entry","entry":{"type":"message","id":"m","message":{"content":"missing role"}}}""")
        }
        assertThrows(WireFormatException::class.java) {
            parseHostFrame("""{"t":"event","event":{"type":"message_end","message":{"role":"assistant","content":[{"text":"missing type"}]}}}""")
        }
    }
}
