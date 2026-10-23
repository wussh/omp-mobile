package dev.omp.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omp.mobile.collab.AgentCommand
import dev.omp.mobile.collab.AgentProgress
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.ReplicaSnapshot
import dev.omp.mobile.collab.SendResult
import dev.omp.mobile.collab.SubagentLifecyclePayload
import dev.omp.mobile.collab.TranscriptResult
import dev.omp.mobile.data.CollabRepository
import dev.omp.mobile.data.CollabSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A host-listed subagent plus whatever real bus telemetry exists for it. */
data class AgentItem(
    val id: String,
    val displayName: String,
    val status: String,
    val hasSessionFile: Boolean,
    val progress: AgentProgress?,
    val lifecycle: SubagentLifecyclePayload?,
) {
    val label: String
        get() = displayName.ifBlank {
            val fromProgress = progress?.agent.orEmpty()
            if (fromProgress.isNotBlank()) fromProgress else id
        }

    val task: String
        get() = progress?.task?.takeIf { it.isNotBlank() }
            ?: lifecycle?.description?.takeIf { it.isNotBlank() }
            ?: ""

    val detail: String?
        get() = progress?.let { current ->
            current.currentTool?.let { tool ->
                val intent = current.currentToolIntent ?: current.lastIntent
                if (intent.isNullOrBlank()) tool else "$tool · $intent"
            }
        }
}

sealed interface AgentDialog {
    data class Chat(val agentId: String) : AgentDialog
    data class Transcript(val agentId: String) : AgentDialog
}

/**
 * Raw JSONL transcript read from the host. `nextByte` preserves the wire's
 * byte-offset semantics; pages are appended only when the host advances it.
 */
data class AgentTranscript(
    val agentId: String,
    val text: String = "",
    val nextByte: Long = 0,
    val loading: Boolean = false,
    val atEnd: Boolean = false,
    val error: String? = null,
)

data class AgentControlsUiState(
    val pairingId: String? = null,
    val connected: Boolean = false,
    val readOnly: Boolean = false,
    val canControl: Boolean = false,
    val agents: List<AgentItem> = emptyList(),
    val selectedAgentId: String? = null,
    val dialog: AgentDialog? = null,
    val chatText: String = "",
    val chatBusy: Boolean = false,
    val pendingCommandAgentId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val transcript: AgentTranscript? = null,
)

/**
 * Subagent panel state. Agents and bus telemetry come only from the live
 * replica; nothing is synthesized when the host has not reported it.
 */
@HiltViewModel
class AgentControlsViewModel @Inject constructor(
    private val repository: CollabRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(AgentControlsUiState())
    val ui: StateFlow<AgentControlsUiState> = _ui.asStateFlow()

    private var transcriptEpoch = 0L
    private var chatEpoch = 0L

    init {
        viewModelScope.launch {
            repository.session.collect { state -> applySession(state) }
        }
    }

    private fun applySession(state: CollabSessionState) {
        val previous = _ui.value
        val replica = state.replica
        val agents = replica.subagents()
        val pairingChanged = previous.pairingId != state.pairingId
        if (pairingChanged) {
            // Never let an in-flight read from the old pairing land in the new one.
            transcriptEpoch++
            chatEpoch++
            _ui.value = AgentControlsUiState(
                pairingId = state.pairingId,
                connected = state.status is ConnectionStatus.Connected,
                readOnly = state.readOnly,
                canControl = state.canMutate,
                agents = agents,
            )
            return
        }

        val selected = previous.selectedAgentId?.takeIf { id -> agents.any { it.id == id } }
        var dialog = previous.dialog
        var transcript = previous.transcript
        when (val active = previous.dialog) {
            is AgentDialog.Chat -> if (agents.none { it.id == active.agentId }) dialog = null
            is AgentDialog.Transcript -> {
                val agent = agents.firstOrNull { it.id == active.agentId }
                if (agent == null || !agent.hasSessionFile) {
                    transcriptEpoch++
                    dialog = null
                    transcript = null
                }
            }

            null -> Unit
        }
        transcript?.let { shown ->
            if (agents.none { it.id == shown.agentId }) {
                transcriptEpoch++
                transcript = null
                if (previous.dialog is AgentDialog.Transcript) dialog = null
            }
        }

        val chatBusy = if (previous.dialog is AgentDialog.Chat) previous.chatBusy else false
        val pendingCommand = previous.pendingCommandAgentId?.takeIf { id -> agents.any { it.id == id } }
        _ui.value = previous.copy(
            connected = state.status is ConnectionStatus.Connected,
            readOnly = state.readOnly,
            canControl = state.canMutate,
            agents = agents,
            selectedAgentId = selected,
            dialog = dialog,
            transcript = transcript,
            chatBusy = chatBusy,
            pendingCommandAgentId = pendingCommand,
        )
    }

    fun selectAgent(agentId: String) {
        if (_ui.value.agents.none { it.id == agentId }) return
        _ui.update { it.copy(selectedAgentId = agentId) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null, messageIsError = false) }
    }

    fun openChat(agentId: String) {
        val state = _ui.value
        val agent = state.agents.firstOrNull { it.id == agentId } ?: return
        if (!state.canControl) {
            _ui.update { it.copy(message = NEED_CONTROL, messageIsError = true) }
            return
        }
        chatEpoch++
        _ui.update {
            it.copy(
                selectedAgentId = agent.id,
                dialog = AgentDialog.Chat(agent.id),
                chatText = "",
                chatBusy = false,
                message = null,
                messageIsError = false,
            )
        }
    }

    fun setChatText(text: String) {
        if (_ui.value.dialog !is AgentDialog.Chat) return
        _ui.update { it.copy(chatText = text) }
    }

    fun sendChat() {
        val state = _ui.value
        val dialog = state.dialog as? AgentDialog.Chat ?: return
        val text = state.chatText
        if (text.isBlank() || state.chatBusy) return
        if (!state.canControl) {
            _ui.update { it.copy(message = NEED_CONTROL, messageIsError = true) }
            return
        }
        val pairingId = state.pairingId
        val epoch = ++chatEpoch
        _ui.update { it.copy(chatBusy = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            val result = repository.sendAgentCommand(AgentCommand.CHAT, dialog.agentId, text)
            val current = _ui.value
            if (epoch != chatEpoch || current.pairingId != pairingId) return@launch
            if (current.dialog != AgentDialog.Chat(dialog.agentId)) return@launch
            when (result) {
                SendResult.Queued -> _ui.update {
                    it.copy(
                        chatBusy = false,
                        chatText = "",
                        message = "Sent to the host. Agent commands are not acknowledged.",
                        messageIsError = false,
                    )
                }

                else -> _ui.update {
                    it.copy(chatBusy = false, message = result.message(), messageIsError = true)
                }
            }
        }
    }

    fun killAgent(agentId: String) = sendCommand(AgentCommand.KILL, agentId)

    fun reviveAgent(agentId: String) = sendCommand(AgentCommand.REVIVE, agentId)

    private fun sendCommand(command: AgentCommand, agentId: String) {
        val state = _ui.value
        if (state.agents.none { it.id == agentId }) return
        if (!state.canControl) {
            _ui.update { it.copy(message = NEED_CONTROL, messageIsError = true) }
            return
        }
        if (state.pendingCommandAgentId != null) return
        val pairingId = state.pairingId
        _ui.update {
            it.copy(
                pendingCommandAgentId = agentId,
                selectedAgentId = agentId,
                message = null,
                messageIsError = false,
            )
        }
        viewModelScope.launch {
            val result = repository.sendAgentCommand(command, agentId)
            val current = _ui.value
            if (current.pairingId != pairingId) return@launch
            _ui.update {
                it.copy(
                    pendingCommandAgentId = null,
                    message = when (result) {
                        SendResult.Queued ->
                            "${command.label} sent to the host. Agent commands are not acknowledged."

                        else -> result.message()
                    },
                    messageIsError = result != SendResult.Queued,
                )
            }
        }
    }

    fun openTranscript(agentId: String) {
        val state = _ui.value
        val agent = state.agents.firstOrNull { it.id == agentId } ?: return
        if (!agent.hasSessionFile) {
            _ui.update {
                it.copy(message = "The host has no session file for this agent.", messageIsError = true)
            }
            return
        }
        _ui.update {
            it.copy(
                selectedAgentId = agent.id,
                dialog = AgentDialog.Transcript(agent.id),
                transcript = AgentTranscript(agentId = agent.id),
                message = null,
                messageIsError = false,
            )
        }
        requestTranscript(agent.id, fromByte = 0, reset = true)
    }

    /** Re-reads from byte 0, replacing the shown text. */
    fun refreshTranscript() {
        val state = _ui.value
        val dialog = state.dialog as? AgentDialog.Transcript ?: return
        val transcript = state.transcript ?: return
        if (transcript.loading) return
        requestTranscript(dialog.agentId, fromByte = 0, reset = true)
    }

    /** Requests the next host chunk from the committed byte offset. */
    fun loadMoreTranscript() {
        val state = _ui.value
        val dialog = state.dialog as? AgentDialog.Transcript ?: return
        val transcript = state.transcript ?: return
        if (transcript.loading || transcript.atEnd) return
        requestTranscript(dialog.agentId, fromByte = transcript.nextByte, reset = false)
    }

    private fun requestTranscript(agentId: String, fromByte: Long, reset: Boolean) {
        val epoch = ++transcriptEpoch
        val pairingId = _ui.value.pairingId
        _ui.update { state ->
            state.copy(
                transcript = AgentTranscript(
                    agentId = agentId,
                    text = if (reset) "" else state.transcript?.text.orEmpty(),
                    nextByte = fromByte,
                    loading = true,
                ),
            )
        }
        viewModelScope.launch {
            val result = repository.fetchTranscript(agentId, fromByte)
            val current = _ui.value
            if (epoch != transcriptEpoch || current.pairingId != pairingId) return@launch
            val transcript = current.transcript ?: return@launch
            if (transcript.agentId != agentId) return@launch
            applyTranscriptResult(agentId, fromByte, result)
        }
    }

    private fun applyTranscriptResult(agentId: String, requestedFrom: Long, result: TranscriptResult) {
        _ui.update { state ->
            val transcript = state.transcript ?: return@update state
            if (transcript.agentId != agentId) return@update state
            when (result) {
                is TranscriptResult.Rows -> {
                    if (result.newSize < requestedFrom) {
                        state.copy(
                            transcript = transcript.copy(
                                loading = false,
                                error = "Host returned a transcript offset behind the requested page.",
                            ),
                        )
                    } else if (result.newSize == requestedFrom && result.text.isNotEmpty()) {
                        state.copy(
                            transcript = transcript.copy(
                                loading = false,
                                error = "Host returned transcript data without advancing the offset.",
                            ),
                        )
                    } else {
                        val atEnd = result.text.isEmpty() && result.newSize == requestedFrom
                        state.copy(
                            transcript = transcript.copy(
                                text = transcript.text + result.text,
                                nextByte = result.newSize,
                                loading = false,
                                atEnd = atEnd,
                                error = null,
                            ),
                        )
                    }
                }

                is TranscriptResult.HostError -> state.copy(
                    transcript = transcript.copy(loading = false, error = result.message),
                )

                TranscriptResult.Timeout -> state.copy(
                    transcript = transcript.copy(
                        loading = false,
                        error = "Host did not answer the transcript request. Load more retries from byte ${transcript.nextByte}.",
                    ),
                )

                TranscriptResult.Unavailable -> state.copy(
                    transcript = transcript.copy(
                        loading = false,
                        error = "Connection lost before the transcript page arrived.",
                    ),
                )

                TranscriptResult.Rejected -> state.copy(
                    transcript = transcript.copy(loading = false, error = "Transcript request was rejected."),
                )
            }
        }
    }

    fun dismissDialog() {
        transcriptEpoch++
        chatEpoch++
        _ui.update {
            it.copy(
                dialog = null,
                transcript = null,
                chatText = "",
                chatBusy = false,
                message = null,
                messageIsError = false,
            )
        }
    }

    private fun ReplicaSnapshot.subagents(): List<AgentItem> {
        val progressById = subagentProgress
        val lifecycleById = subagentLifecycle
        return agents
            .filter { it.kind == "sub" }
            .map { agent ->
                AgentItem(
                    id = agent.id,
                    displayName = agent.displayName,
                    status = agent.status,
                    hasSessionFile = agent.hasSessionFile,
                    progress = progressById[agent.id]?.progress,
                    lifecycle = lifecycleById[agent.id],
                )
            }
    }

    private fun SendResult.message(): String = when (this) {
        SendResult.Queued -> "Queued."
        SendResult.PendingConnection -> "The command has not been sent."
        SendResult.NotConnected -> "Not connected. Wait for the session to be live."
        SendResult.ReadOnly -> NEED_CONTROL
        SendResult.Rejected -> "The command was rejected; the active session may have changed."
    }

    private val AgentCommand.label: String
        get() = when (this) {
            AgentCommand.CHAT -> "Chat"
            AgentCommand.KILL -> "Kill"
            AgentCommand.REVIVE -> "Revive"
        }

    private companion object {
        const val NEED_CONTROL = "Connect with a Control session to command agents."
    }
}
