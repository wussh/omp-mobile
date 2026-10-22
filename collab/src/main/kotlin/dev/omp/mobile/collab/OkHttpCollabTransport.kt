package dev.omp.mobile.collab

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

interface TransportSession {
    /** @return false when the frame could not be enqueued on the current socket. */
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int = 1000, reason: String = "")
}

interface TransportListener {
    fun onOpen()
    fun onBinary(bytes: ByteArray)
    fun onText(text: String)
    /** Remote close frame observed; the transport already replied with 1000. */
    fun onClosing(code: Int, reason: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

fun interface TransportFactory {
    fun create(url: String, listener: TransportListener): TransportSession
}

/**
 * OkHttp guest transport.
 *
 * The URL carries only `?role=guest`; credentials never leave the encrypted
 * hello/frames. Binary messages are session frames, TEXT messages are relay
 * controls. No retry loop lives here: [CollabClient] owns reconnect policy.
 */
class OkHttpCollabTransport(
    private val client: OkHttpClient = defaultClient(),
) : TransportFactory {

    override fun create(url: String, listener: TransportListener): TransportSession {
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, Listener(listener))
        return object : TransportSession {
            override fun send(bytes: ByteArray): Boolean = socket.send(ByteString.of(*bytes))

            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }
        }
    }

    private class Listener(private val listener: TransportListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener.onBinary(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener.onText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Reply exactly once, then let the client decide whether to reconnect.
            webSocket.close(1000, "")
            listener.onClosing(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener.onFailure(t)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
