package dev.omp.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.omp.mobile.collab.AgentCommand
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.PromptImage
import dev.omp.mobile.collab.SendResult
import dev.omp.mobile.collab.TranscriptResult
import dev.omp.mobile.data.CollabRepository
import dev.omp.mobile.data.CollabSessionState
import dev.omp.mobile.data.PairingResult
import dev.omp.mobile.data.SavedPairing
import dev.omp.mobile.data.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatDraftInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test
    fun imageOnlyDraftSurvivesPickerPauseAndRejectedSend() = runBlocking {
        val repository = DraftRepository()
        lateinit var vm: ChatViewModel
        val imageFile = photo()
        main { vm = ChatViewModel(context, repository) }
        try {
            // The Android system photo picker pauses our foreground-only socket.
            main {
                repository.session.value = repository.session.value.copy(status = ConnectionStatus.Paused)
                vm.addImages(listOf(Uri.fromFile(imageFile)))
            }
            withTimeout(10_000) { vm.preparingImages.first { !it } }
            assertEquals(1, vm.attachments.value.size)
            main {
                repository.session.value = repository.session.value.copy(status = ConnectionStatus.Connected)
                vm.send()
            }
            withTimeout(5_000) { repository.promptStarted.await() }
            repository.promptResult.complete(SendResult.Rejected)
            withTimeout(5_000) { vm.sendingPrompt.first { !it } }
            assertEquals("", vm.draft.value)
            assertEquals(1, vm.attachments.value.size)
            assertTrue(vm.info.value.orEmpty().contains("kept"))
        } finally {
            main { vm.viewModelScope.cancel() }
            imageFile.delete()
        }
    }

    @Test
    fun sendCompletionClearsOnlyOriginatingPairingDraft() = runBlocking {
        val repository = DraftRepository()
        lateinit var vm: ChatViewModel
        main { vm = ChatViewModel(context, repository) }
        try {
            main { vm.setDraft("Pairing A message"); vm.send() }
            withTimeout(5_000) { repository.promptStarted.await() }
            main { repository.session.value = repository.session.value.copy(pairingId = "b") }
            instrumentation.waitForIdleSync()
            main { vm.setDraft("Keep pairing B message") }
            repository.promptResult.complete(SendResult.Queued)
            withTimeout(5_000) { vm.sendingPrompt.first { !it } }
            assertEquals("Keep pairing B message", vm.draft.value)
            main { repository.session.value = repository.session.value.copy(pairingId = "a") }
            instrumentation.waitForIdleSync()
            assertEquals("", vm.draft.value)
            main { repository.session.value = repository.session.value.copy(pairingId = "b") }
            instrumentation.waitForIdleSync()
            assertEquals("Keep pairing B message", vm.draft.value)
        } finally {
            main { vm.viewModelScope.cancel() }
        }
    }

    private fun photo(): File {
        val file = File(context.cacheDir, "draft-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return file
    }

    private class DraftRepository : CollabRepository {
        override val pairings = MutableStateFlow(listOf(
            SavedPairing("a", "A", "wss://example.com", "room-a", true),
            SavedPairing("b", "B", "wss://example.com", "room-b", true),
        ))
        override val session = MutableStateFlow(CollabSessionState(
            status = ConnectionStatus.Connected, hasWriteToken = true, pairingId = "a",
        ))
        override val nickname = MutableStateFlow("Test")
        override val theme = MutableStateFlow(ThemeMode.SYSTEM)
        val promptStarted = CompletableDeferred<Unit>()
        val promptResult = CompletableDeferred<SendResult>()
        override suspend fun sendPrompt(text: String, images: List<PromptImage>): SendResult {
            promptStarted.complete(Unit)
            return promptResult.await()
        }
        override suspend fun pair(rawLink: String, label: String?): PairingResult = error("Unexpected pairing")
        override suspend fun select(id: String): Unit = error("Unexpected selection")
        override suspend fun rename(id: String, label: String): Unit = error("Unexpected rename")
        override suspend fun forget(id: String): Unit = error("Unexpected deletion")
        override fun retry(): Unit = error("Unexpected retry")
        override fun disconnect(): Unit = error("Unexpected disconnect")
        override suspend fun interrupt(): SendResult = error("Unexpected interrupt")
        override suspend fun respond(reqId: Long, value: String?): SendResult = error("Unexpected response")
        override suspend fun sendAgentCommand(command: AgentCommand, agentId: String, text: String?): SendResult = error("Unexpected command")
        override suspend fun fetchTranscript(agentId: String, fromByte: Long): TranscriptResult = error("Unexpected transcript")
        override suspend fun setNickname(value: String): Unit = error("Unexpected nickname")
        override suspend fun setTheme(mode: ThemeMode): Unit = error("Unexpected theme")
        override fun clearNotice(): Unit = error("Unexpected notice")
    }
}
