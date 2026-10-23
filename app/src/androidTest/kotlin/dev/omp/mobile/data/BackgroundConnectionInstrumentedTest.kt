package dev.omp.mobile.data

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.omp.mobile.MainActivity
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.RoomCipher
import dev.omp.mobile.collab.packEnvelope
import dev.omp.mobile.collab.unpackEnvelope
import dev.omp.mobile.ui.chat.ChatViewModel
import dev.omp.mobile.ui.sessions.SessionsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exercises the actual repository, WebSocket transport, Android service, and notification action. */
@RunWith(AndroidJUnit4::class)
class BackgroundConnectionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test
    fun backgroundReconnectDrainsOnlyAfterSnapshotAndDisconnectStopsService() = runBlocking {
        val key = ByteArray(32) { it.toByte() }
        val cipher = RoomCipher.fromKey(key)
        val sockets = LinkedBlockingQueue<WebSocket>()
        val allSockets = CopyOnWriteArrayList<WebSocket>()
        val prompts = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        repeat(5) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    allSockets.add(webSocket)
                    sockets.add(webSocket)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val frame = Json.parseToJsonElement(cipher.open(unpackEnvelope(bytes.toByteArray())!!.payload)).jsonObject
                    when (frame["t"]?.jsonPrimitive?.content) {
                        "hello" -> send(webSocket, cipher, WELCOME)
                        "prompt" -> prompts.add(frame["text"]!!.jsonPrimitive.content)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        lateinit var sessions: SessionsViewModel
        lateinit var chat: ChatViewModel
        var pairingId: String? = null
        var initialized = false
        var stage = "pairing and initial snapshot"
        var failure: Throwable? = null
        try {
            main {
                sessions = ViewModelProvider(activity)[SessionsViewModel::class.java]
                chat = ViewModelProvider(activity)[ChatViewModel::class.java]
                initialized = true
                sessions.pair(
                    PairingCredentials("ws://localhost:${server.port}", "AbCdEf123456_-Xy", key, ByteArray(16) { 7 }),
                    "Background runtime test",
                )
            }
            pairingId = (withTimeout(15_000) { sessions.pairingResult.first { it is PairingResult.Saved } } as PairingResult.Saved).id
            val first = checkNotNull(sockets.poll(15, TimeUnit.SECONDS)) { "First socket did not join" }
            withTimeout(15_000) { sessions.session.first { it.status is ConnectionStatus.Syncing } }
            stage = "accepting the first pending prompt"
            main { chat.setDraft("first pending"); chat.send() }
            withTimeout(5_000) { sessions.session.first { it.queuedPromptCount == 1 } }
            withTimeout(5_000) { chat.sendingPrompt.first { !it } }
            assertEquals("", chat.draft.value)
            stage = "moving the app to the background"
            main { assertTrue("App task must move behind the foreground task", activity.moveTaskToBack(true)) }
            eventually {
                var stopped = false
                main { stopped = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                stopped
            }
            eventually { hasForegroundService() }
            stage = "reconnecting while backgrounded"
            assertTrue(first.close(1001, "fixture connection change"))
            val second = checkNotNull(sockets.poll(30, TimeUnit.SECONDS)) { "No reconnect while app was backgrounded" }
            withTimeout(15_000) { sessions.session.first { it.status is ConnectionStatus.Syncing } }
            main { chat.setDraft("second pending"); chat.send() }
            withTimeout(5_000) { sessions.session.first { it.queuedPromptCount == 2 } }
            assertTrue("No prompt before final snapshot", prompts.isEmpty())
            send(second, cipher, FINAL_CHUNK)
            assertEquals("first pending", prompts.poll(10, TimeUnit.SECONDS))
            assertEquals("second pending", prompts.poll(10, TimeUnit.SECONDS))
            withTimeout(5_000) { sessions.session.first { it.status == ConnectionStatus.Connected && it.queuedPromptCount == 0 } }
            stage = "checking no replay after a second background reconnect"
            assertTrue(second.close(1001, "fixture connection change"))
            val third = checkNotNull(sockets.poll(30, TimeUnit.SECONDS)) { "Second background reconnect did not occur" }
            withTimeout(15_000) { sessions.session.first { it.status is ConnectionStatus.Syncing } }
            send(third, cipher, FINAL_CHUNK)
            withTimeout(5_000) { sessions.session.first { it.status == ConnectionStatus.Connected } }
            assertEquals("Already dispatched prompts must not replay", null, prompts.poll(2, TimeUnit.SECONDS))
            stage = "disconnecting through the foreground notification"
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            val disconnect = notifications.flatMap { it.notification.actions?.toList().orEmpty() }
                .single { it.title.toString() == "Disconnect" }
            disconnect.actionIntent.send()
            withTimeout(5_000) { sessions.session.first { it.status == ConnectionStatus.Idle } }
            eventually { !hasForegroundService() }
            assertFalse("Disconnect must stop reconnect attempts", sockets.poll(2, TimeUnit.SECONDS) != null)
            stage = "restoring the UI for an explicit retry"
            main {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            eventually {
                var started = false
                main { started = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                started
            }
            main { sessions.retry() }
            val fourth = checkNotNull(sockets.poll(15, TimeUnit.SECONDS)) { "Explicit retry did not reconnect" }
            withTimeout(15_000) { sessions.session.first { it.status is ConnectionStatus.Syncing } }
            send(fourth, cipher, FINAL_CHUNK)
            stage = "publishing the foreground resync result"
            withTimeout(15_000) { sessions.session.first { it.status == ConnectionStatus.Connected } }
            eventually { hasForegroundService() }
            val oldNotificationTime = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single { it.notification.actions?.any { action -> action.title.toString() == "Disconnect" } == true }.postTime
            stage = "retrying while the previous service is stopping"
            main { sessions.disconnect(); sessions.retry() }
            val fifth = checkNotNull(sockets.poll(15, TimeUnit.SECONDS)) { "Immediate retry was lost after disconnect" }
            withTimeout(15_000) { sessions.session.first { it.status is ConnectionStatus.Syncing } }
            eventually {
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .any { it.postTime > oldNotificationTime && it.notification.actions?.any { action -> action.title.toString() == "Disconnect" } == true }
            }
            main { assertTrue(activity.moveTaskToBack(true)) }
            eventually {
                var stopped = false
                main { stopped = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                stopped
            }
            eventually { hasForegroundService() }
            send(fifth, cipher, FINAL_CHUNK)
            withTimeout(5_000) { sessions.session.first { it.status == ConnectionStatus.Connected } }
            val finalDisconnect = context.getSystemService(NotificationManager::class.java).activeNotifications
                .flatMap { it.notification.actions?.toList().orEmpty() }
                .single { it.title.toString() == "Disconnect" }
            finalDisconnect.actionIntent.send()
            withTimeout(5_000) { sessions.session.first { it.status == ConnectionStatus.Idle } }
            eventually { !hasForegroundService() }
        } catch (error: Throwable) {
            val status = if (initialized) sessions.session.value.status else "not initialized"
            val detail = AssertionError("Background scenario failed at: $stage; status=$status", error)
            failure = detail
            throw detail
        } finally {
            if (initialized) {
                main { sessions.disconnect(); pairingId?.let(sessions::forget) }
            }
            main { activity.finish() }
            allSockets.forEach { it.close(1000, "fixture complete") }
            try {
                server.shutdown()
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasForegroundService(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.packageName == context.packageName && it.foreground }

    private suspend fun eventually(predicate: () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(100)
    }

    private fun send(socket: WebSocket, cipher: RoomCipher, frame: String) {
        check(socket.send(packEnvelope(0, cipher.seal(frame)).toByteString()))
    }

    companion object {
        private const val WELCOME = """{"t":"welcome","proto":3,"header":{"id":"background-test","title":"Test","cwd":"/test"},"state":{"isStreaming":false,"queuedMessageCount":0,"cwd":"/test","participants":[]},"agents":[],"entryCount":1}"""
        private const val FINAL_CHUNK = """{"t":"snapshot-chunk","entries":[{"id":"one","parentId":null,"timestamp":"t","type":"message","message":{"role":"user","content":"history","timestamp":1}}],"final":true}"""
    }
}
