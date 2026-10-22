package dev.omp.mobile.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omp.mobile.collab.ConnectionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omp.mobile.collab.PromptImage
import dev.omp.mobile.collab.SendResult
import dev.omp.mobile.data.CollabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


@HiltViewModel
class ChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CollabRepository,
) : ViewModel() {
    val session = repository.session

    private val drafts = mutableMapOf<String, String>()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    private val _sendWarning = MutableStateFlow<String?>(null)
    val sendWarning: StateFlow<String?> = _sendWarning.asStateFlow()

    private val _interruptPending = MutableStateFlow(false)
    val interruptPending: StateFlow<Boolean> = _interruptPending.asStateFlow()

    private val _answeredRequests = MutableStateFlow<Set<Long>>(emptySet())
    val answeredRequests: StateFlow<Set<Long>> = _answeredRequests.asStateFlow()

    private val attachmentsByPairing = mutableMapOf<String, List<PendingImage>>()
    private val _attachments = MutableStateFlow<List<PendingImage>>(emptyList())
    val attachments: StateFlow<List<PendingImage>> = _attachments.asStateFlow()

    private val _preparingImages = MutableStateFlow(false)
    val preparingImages: StateFlow<Boolean> = _preparingImages.asStateFlow()

    private val _sendingPrompt = MutableStateFlow(false)
    val sendingPrompt: StateFlow<Boolean> = _sendingPrompt.asStateFlow()

    private var currentPairingId: String? = null
    private var sendingInFlight = false

    init {
        viewModelScope.launch {
            repository.session.collect { state ->
                if (state.pairingId != currentPairingId) {
                    currentPairingId = state.pairingId
                    _draft.value = drafts[state.pairingId].orEmpty()
                    _attachments.value = attachmentsByPairing[state.pairingId].orEmpty()
                }
                if (!state.canMutate) {
                    _interruptPending.value = false
                } else if (_interruptPending.value && state.replica.state?.isStreaming == false) {
                    _interruptPending.value = false
                }
                if (sendingInFlight) {
                    when (state.status) {
                        is ConnectionStatus.Reconnecting, ConnectionStatus.WaitingForNetwork,
                        is ConnectionStatus.Failed, is ConnectionStatus.Ended, ConnectionStatus.Paused,
                        -> {
                            sendingInFlight = false
                            _sendWarning.value =
                                "Connection lost after the command was sent. It may already have run; check the transcript after resync before sending again."
                        }

                        else -> Unit
                    }
                }
                val activeId = state.replica.uiRequest?.reqId
                if (activeId == null && _answeredRequests.value.isNotEmpty()) {
                    _answeredRequests.value = emptySet()
                }
            }
        }
    }

    fun setDraft(text: String) {
        _draft.value = text
        currentPairingId?.let { drafts[it] = text }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty() || _preparingImages.value) return
        val selected = repository.session.value
        val pairingId = selected.pairingId ?: return
        // The system picker can pause the connection; drafting does not require a live socket.
        if (!selected.hasWriteToken || selected.readOnly) {
            _info.value = "View sessions cannot attach images."
            return
        }
        val slots = MAX_PROMPT_IMAGES - attachmentsByPairing[pairingId].orEmpty().size
        if (slots <= 0) {
            _info.value = "Up to $MAX_PROMPT_IMAGES images per prompt."
            return
        }
        _preparingImages.value = true
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    uris.take(slots).mapNotNull { uri ->
                        try { preparePromptImage(context, uri) } catch (_: Exception) { null }
                    }
                }
                if (repository.pairings.value.none { it.id == pairingId }) return@launch
                val current = attachmentsByPairing[pairingId].orEmpty()
                var total = current.sumOf { it.base64.length }
                val accepted = current.toMutableList()
                for (image in prepared) {
                    if (accepted.size < MAX_PROMPT_IMAGES && total + image.base64.length <= MAX_PROMPT_IMAGE_BASE64) {
                        accepted += image
                        total += image.base64.length
                    }
                }
                attachmentsByPairing[pairingId] = accepted
                if (currentPairingId == pairingId) {
                    _attachments.value = accepted
                    if (accepted.size - current.size < uris.size) {
                        _info.value = "Some images could not be attached. Use up to $MAX_PROMPT_IMAGES decodable images within the prompt size limit."
                    }
                }
            } finally {
                _preparingImages.value = false
            }
        }
    }

    fun removeImage(id: String) {
        val next = _attachments.value.filterNot { it.id == id }
        _attachments.value = next
        currentPairingId?.let { attachmentsByPairing[it] = next }
    }

    fun send() {
        val pairingId = currentPairingId ?: return
        val text = _draft.value
        val images = _attachments.value
        if ((text.isBlank() && images.isEmpty()) || _preparingImages.value || _sendingPrompt.value) return
        val payload = images.map { PromptImage(it.base64, it.mimeType) }
        _sendingPrompt.value = true
        viewModelScope.launch {
            try {
                when (repository.sendPrompt(text, payload)) {
                    SendResult.Queued -> {
                        if (drafts[pairingId] == text) drafts[pairingId] = ""
                        val sentIds = images.mapTo(mutableSetOf()) { it.id }
                        val remaining = attachmentsByPairing[pairingId].orEmpty().filterNot { it.id in sentIds }
                        attachmentsByPairing[pairingId] = remaining
                        if (currentPairingId == pairingId) {
                            sendingInFlight = true
                            _draft.value = drafts[pairingId].orEmpty()
                            _attachments.value = remaining
                            _info.value = null
                        }
                    }
                    SendResult.NotConnected -> _info.value = "Not connected; draft and images kept."
                    SendResult.ReadOnly -> _info.value = "View sessions cannot send prompts."
                    SendResult.Rejected -> _info.value = "Prompt could not be sent; draft and images kept."
                }
            } finally {
                _sendingPrompt.value = false
            }
        }
    }

    fun interrupt() {
        if (_interruptPending.value) return
        _interruptPending.value = true
        viewModelScope.launch {
            when (repository.interrupt()) {
                SendResult.Queued -> Unit
                else -> {
                    _interruptPending.value = false
                    _info.value = "Interrupt could not be sent."
                }
            }
        }
    }

    fun respondSelect(reqId: Long, label: String) {
        if (reqId in _answeredRequests.value) return
        _answeredRequests.value = _answeredRequests.value + reqId
        viewModelScope.launch {
            val result = repository.respond(reqId, label)
            if (result != SendResult.Queued) {
                _answeredRequests.value = _answeredRequests.value - reqId
                _info.value = responseError(result)
            }
        }
    }

    fun submitEditor(reqId: Long, text: String) {
        if (reqId in _answeredRequests.value) return
        _answeredRequests.value = _answeredRequests.value + reqId
        viewModelScope.launch {
            val result = repository.respond(reqId, text)
            if (result != SendResult.Queued) {
                _answeredRequests.value = _answeredRequests.value - reqId
                _info.value = responseError(result)
            }
        }
    }

    fun cancelEditor(reqId: Long) {
        if (reqId in _answeredRequests.value) return
        _answeredRequests.value = _answeredRequests.value + reqId
        viewModelScope.launch {
            val result = repository.respond(reqId, null)
            if (result != SendResult.Queued) {
                _answeredRequests.value = _answeredRequests.value - reqId
                _info.value = responseError(result)
            }
        }
    }

    fun clearInfo() {
        _info.value = null
    }

    fun dismissSendWarning() {
        _sendWarning.value = null
    }

    fun clearNotice() = repository.clearNotice()

    fun retry() = repository.retry()

    fun disconnect() = repository.disconnect()


    private fun responseError(result: SendResult): String = when (result) {
        SendResult.NotConnected -> "Not connected."
        SendResult.ReadOnly -> "View sessions cannot answer."
        SendResult.Rejected -> "Response could not be sent."
        SendResult.Queued -> ""
    }

}
