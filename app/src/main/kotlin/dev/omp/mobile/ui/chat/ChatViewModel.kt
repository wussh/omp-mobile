package dev.omp.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.SendResult
import dev.omp.mobile.data.CollabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
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

    private var currentPairingId: String? = null
    private var sendingInFlight = false

    init {
        viewModelScope.launch {
            repository.session.collect { state ->
                if (state.pairingId != currentPairingId) {
                    currentPairingId = state.pairingId
                    _draft.value = drafts[state.pairingId].orEmpty()
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
                                "Koneksi terputus setelah perintah dikirim. Perintah mungkin sudah dijalankan; periksa transcript setelah resync sebelum mengirim ulang."
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

    fun send() {
        val text = _draft.value
        if (text.isBlank()) return
        viewModelScope.launch {
            when (repository.sendPrompt(text)) {
                SendResult.Queued -> {
                    sendingInFlight = true
                    currentPairingId?.let { drafts[it] = "" }
                    _draft.value = ""
                    _info.value = null
                }

                SendResult.NotConnected -> _info.value = "Belum terhubung; draft dipertahankan."
                SendResult.ReadOnly -> _info.value = "Sesi View tidak dapat mengirim perintah."
                SendResult.Rejected -> _info.value = "Perintah tidak dapat dikirim."
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
                    _info.value = "Interrupt tidak dapat dikirim."
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
        SendResult.NotConnected -> "Belum terhubung."
        SendResult.ReadOnly -> "Sesi View tidak dapat menjawab."
        SendResult.Rejected -> "Jawaban tidak dapat dikirim."
        SendResult.Queued -> ""
    }
}
