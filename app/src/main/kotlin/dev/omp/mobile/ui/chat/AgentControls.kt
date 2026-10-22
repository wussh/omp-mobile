package dev.omp.mobile.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Collapsed-by-default subagent panel. Lists only agents the host reported
 * (`kind=sub`) and exposes chat, kill/revive, and transcript reads backed by
 * the real wire frames.
 */
@Composable
fun AgentControls(viewModel: AgentControlsViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (expanded) "Hide subagents" else "Show subagents (${state.agents.size})")
            }
            Text(
                text = if (state.agents.isEmpty()) {
                    "No subagents reported"
                } else {
                    state.agents
                        .groupingBy { it.status.ifBlank { "unknown" } }
                        .eachCount()
                        .entries
                        .joinToString(" · ") { "${it.value} ${it.key}" }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        if (expanded) {
            // Bounded panel: a long subagent list must not starve the transcript.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!state.connected) {
                    Text(
                        "Subagent details refresh when the session is connected.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (!state.canControl) {
                    Text(
                        if (state.readOnly) {
                            "View session: chat, kill, and revive are unavailable. Transcripts remain readable."
                        } else {
                            "Control session required for chat, kill, and revive. Transcripts remain readable."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (state.agents.isEmpty()) {
                    Text(
                        "The host has not listed any subagents for this session.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    state.agents.forEach { agent ->
                        AgentRow(
                            agent = agent,
                            selected = state.selectedAgentId == agent.id,
                            canControl = state.canControl,
                            pending = state.pendingCommandAgentId == agent.id,
                            onSelect = { viewModel.selectAgent(agent.id) },
                            onChat = { viewModel.openChat(agent.id) },
                            onKill = { viewModel.killAgent(agent.id) },
                            onRevive = { viewModel.reviveAgent(agent.id) },
                            onTranscript = { viewModel.openTranscript(agent.id) },
                        )
                    }
                }
            }
        }

        state.message?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = viewModel::clearMessage,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Dismiss") }
            }
        }
    }

    when (val dialog = state.dialog) {
        is AgentDialog.Chat -> {
            val agent = state.agents.firstOrNull { it.id == dialog.agentId }
            ChatDialog(
                agentLabel = agent?.label ?: dialog.agentId,
                text = state.chatText,
                busy = state.chatBusy,
                canControl = state.canControl,
                message = state.message,
                messageIsError = state.messageIsError,
                onTextChange = viewModel::setChatText,
                onSend = viewModel::sendChat,
                onClose = viewModel::dismissDialog,
            )
        }

        is AgentDialog.Transcript -> TranscriptDialog(
            state = state,
            onRefresh = viewModel::refreshTranscript,
            onLoadMore = viewModel::loadMoreTranscript,
            onClose = viewModel::dismissDialog,
        )

        null -> Unit
    }
}

@Composable
private fun AgentRow(
    agent: AgentItem,
    selected: Boolean,
    canControl: Boolean,
    pending: Boolean,
    onSelect: () -> Unit,
    onChat: () -> Unit,
    onKill: () -> Unit,
    onRevive: () -> Unit,
    onTranscript: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable(onClick = onSelect),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(agent.label, style = MaterialTheme.typography.titleSmall)
            if (selected) {
                Text("Selected agent", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Status: ${agent.status.ifBlank { "unknown" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (agent.task.isNotBlank()) {
                Text(agent.task, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            agent.detail?.let {
                Text("Now: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            agent.progress?.let { progress ->
                val metrics = buildString {
                    append("${progress.toolCount} tools")
                    if (progress.tokens > 0) append(" · ${progress.tokens} tokens")
                    if (progress.durationMs > 0) append(" · ${progress.durationMs / 1000}s")
                    progress.resolvedModel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                Text(metrics, style = MaterialTheme.typography.labelSmall)
                progress.recentOutput.lastOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            agent.lifecycle?.status?.takeIf { it.isNotBlank() }?.let {
                Text("Lifecycle: $it", style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onChat,
                    enabled = canControl,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Chat") }
                if (agent.canKill) {
                    TextButton(
                        onClick = onKill,
                        enabled = canControl && !pending,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (pending) "Sending…" else "Kill") }
                }
                if (agent.canRevive) {
                    TextButton(
                        onClick = onRevive,
                        enabled = canControl && !pending,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (pending) "Sending…" else "Revive") }
                }
                if (agent.hasSessionFile) {
                    TextButton(
                        onClick = onTranscript,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Transcript") }
                }
            }
        }
    }
}

private val AgentItem.canKill: Boolean
    get() = status == "running" || status == "idle"

private val AgentItem.canRevive: Boolean
    get() = status == "parked" || status == "aborted"

@Composable
private fun ChatDialog(
    agentLabel: String,
    text: String,
    busy: Boolean,
    canControl: Boolean,
    message: String?,
    messageIsError: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Chat with $agentLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The host does not acknowledge agent commands; the reply appears in the subagent transcript.",
                    style = MaterialTheme.typography.bodySmall,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSend,
                enabled = canControl && !busy && text.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (busy) "Sending…" else "Send") }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Close") }
        },
    )
}

@Composable
private fun TranscriptDialog(
    state: AgentControlsUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
) {
    val transcript = state.transcript
    val agent = state.agents.firstOrNull { it.id == transcript?.agentId }
    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Transcript · ${agent?.label ?: transcript?.agentId.orEmpty()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Close") }
                }
                Text(
                    "Raw JSONL from the host session file (selectable).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (transcript == null) {
                    Text("No transcript loaded.", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                Text(
                    when {
                        transcript.loading && transcript.text.isEmpty() -> "Loading transcript…"
                        transcript.loading -> "Loading more from byte ${transcript.nextByte}…"
                        transcript.atEnd -> "End of available transcript at byte ${transcript.nextByte}."
                        else -> "Committed offset: byte ${transcript.nextByte}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                transcript.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 160.dp, max = 420.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (transcript.text.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (transcript.loading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    "No transcript bytes returned.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = transcript.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !transcript.loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Refresh") }
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !transcript.loading && !transcript.atEnd,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Load more") }
                }
            }
        }
    }
}
