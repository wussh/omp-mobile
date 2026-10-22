package dev.omp.mobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omp.mobile.collab.ActiveTool
import dev.omp.mobile.collab.BranchSummaryEntry
import dev.omp.mobile.collab.CollabUiRequest
import dev.omp.mobile.collab.CompactionEntry
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.ContentBlock
import dev.omp.mobile.collab.CustomMessageEntry
import dev.omp.mobile.collab.MessageEntry
import dev.omp.mobile.collab.ModelChangeEntry
import dev.omp.mobile.collab.SessionEntry
import dev.omp.mobile.collab.ThinkingLevelChangeEntry
import dev.omp.mobile.collab.UnknownEntry
import dev.omp.mobile.collab.WireMessage
import kotlinx.coroutines.flow.collect
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSessions: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val warning by viewModel.sendWarning.collectAsStateWithLifecycle()
    val interruptPending by viewModel.interruptPending.collectAsStateWithLifecycle()
    val answered by viewModel.answeredRequests.collectAsStateWithLifecycle()
    val replica = session.replica

    val entries = remember(replica.entries) {
        replica.entries.filter { it.isRenderable() }
    }
    val listState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) followTail = !listState.canScrollForward
        }
    }

    val committedToolIds = remember(replica.entries) {
        val ids = mutableSetOf<String>()
        for (entry in replica.entries) {
            if (entry !is MessageEntry) continue
            val message = entry.message as? WireMessage.Assistant ?: continue
            for (block in message.content) if (block is ContentBlock.ToolCall) ids += block.id
        }
        ids
    }
    val tailTools: List<ActiveTool> = remember(replica.activeTools, committedToolIds, replica.stream) {
        replica.activeTools.values.filter { tool ->
            tool.toolCallId !in committedToolIds &&
                replica.stream?.content?.none { it is ContentBlock.ToolCall && it.id == tool.toolCallId } != false
        }
    }
    val hasStream = replica.stream != null
    val showWorking = replica.working && replica.stream == null && replica.activeTools.isEmpty()
    val itemCount = entries.size + (if (hasStream) 1 else 0) + (if (tailTools.isNotEmpty()) 1 else 0) +
        (if (showWorking) 1 else 0)

    LaunchedEffect(itemCount, replica.stream, replica.working) {
        if (followTail && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(session)
            ConnectionBanner(
                session = session,
                onRetry = viewModel::retry,
                onStop = viewModel::disconnect,
                onOpenSessions = onOpenSessions,
            )
            warning?.let {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissSendWarning) { Text("Tutup") }
                    }
                }
            }
            info?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearInfo) { Text("Tutup") }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (itemCount == 0) {
                    item("empty") {
                        Column(Modifier.fillMaxWidth().padding(32.dp)) {
                            Text(
                                if (session.status is ConnectionStatus.Syncing) "Menyinkronkan transcript…" else "Belum ada aktivitas",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    JournalRow(entry, replica.toolResults, replica.activeTools)
                }
                if (hasStream) {
                    item(key = "stream-ghost") {
                        AssistantRow(
                            message = replica.stream!!,
                            results = replica.toolResults,
                            active = replica.activeTools,
                            pending = !replica.streamDone,
                        )
                    }
                }
                if (tailTools.isNotEmpty()) {
                    item(key = "active-tools") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tailTools.forEach { tool ->
                                ToolCard(
                                    toolCallId = tool.toolCallId,
                                    name = tool.toolName,
                                    intent = tool.intent,
                                    args = tool.args,
                                    partialResult = tool.partialResult,
                                    result = replica.toolResults[tool.toolCallId],
                                    running = true,
                                )
                            }
                        }
                    }
                }
                if (showWorking) {
                    item(key = "working") {
                        Text("thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!followTail) {
                SmallFloatingActionButton(
                    onClick = { followTail = true },
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Kembali ke terbaru")
                }
            }

            Composer(
                session = session,
                draft = draft,
                interruptPending = interruptPending,
                onDraft = viewModel::setDraft,
                onSend = viewModel::send,
                onInterrupt = viewModel::interrupt,
            )
        }

        val request = replica.uiRequest
        if (request != null) {
            when (request) {
                is CollabUiRequest.Select -> SelectRequestDialog(
                    request = request,
                    answered = request.reqId in answered,
                    onSelect = { viewModel.respondSelect(request.reqId, it) },
                )

                is CollabUiRequest.Editor -> EditorRequestDialog(
                    request = request,
                    answered = request.reqId in answered,
                    onSubmit = { viewModel.submitEditor(request.reqId, it) },
                    onCancel = { viewModel.cancelEditor(request.reqId) },
                )
            }
        }
    }
}

@Composable
private fun Header(session: dev.omp.mobile.data.CollabSessionState) {
    val replica = session.replica
    val state = replica.state
    val title = replica.header?.title
        ?: state?.sessionName
        ?: session.pairingLabel
        ?: "Chat"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = {},
                label = { Text(if (session.readOnly || !session.hasWriteToken) "View" else "Control") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cwdBasename(state?.cwd)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state?.model?.let { model ->
                val name = model.name.ifBlank { model.id }
                if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall)
            }
            state?.contextUsage?.percent?.let {
                Text(String.format(Locale.US, "ctx %.0f%%", it), style = MaterialTheme.typography.bodySmall)
            }
            state?.queuedMessageCount?.takeIf { it > 0 }?.let {
                Text("antrean $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        val subs = replica.agents.filter { it.kind == "sub" }
        if (subs.isNotEmpty()) {
            Text(
                "subagent: ${subs.size} (${subs.count { it.status == "running" }} berjalan)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(statusLabel(session.status), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ConnectionBanner(
    session: dev.omp.mobile.data.CollabSessionState,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val status = session.status
    val text = when (status) {
        ConnectionStatus.Connecting -> "Menyambungkan…"
        is ConnectionStatus.Syncing -> "Menyinkronkan ${status.received}/${status.total}…"
        ConnectionStatus.Connected -> return
        is ConnectionStatus.Reconnecting -> "Menyambungkan kembali (percobaan ${status.attempt})…"
        ConnectionStatus.WaitingForNetwork -> "Menunggu jaringan…"
        ConnectionStatus.Paused -> "Dijeda saat aplikasi di latar. Buka lagi untuk menyambung."
        ConnectionStatus.Idle -> "Belum terhubung."
        is ConnectionStatus.Ended -> "Sesi berakhir: ${status.reason}"
        is ConnectionStatus.Failed -> "Gagal: ${status.message}"
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    is ConnectionStatus.Failed, is ConnectionStatus.Ended, ConnectionStatus.Idle -> {
                        FilledTonalButton(onClick = onRetry) { Text("Coba lagi") }
                        TextButton(onClick = onOpenSessions) { Text("Pair ulang") }
                    }

                    is ConnectionStatus.Reconnecting -> {
                        TextButton(onClick = onStop) { Text("Berhenti menyambung") }
                        TextButton(onClick = onOpenSessions) { Text("Pair ulang") }
                    }

                    ConnectionStatus.WaitingForNetwork, ConnectionStatus.Paused -> {
                        TextButton(onClick = onOpenSessions) { Text("Pair ulang") }
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Composer(
    session: dev.omp.mobile.data.CollabSessionState,
    draft: String,
    interruptPending: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val busy = session.replica.working || session.replica.state?.isStreaming == true
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!session.canMutate) {
            Text(
                when {
                    session.readOnly || !session.hasWriteToken -> "Sesi View: hanya membaca transcript."
                    session.status is ConnectionStatus.Connected -> "Tidak dapat mengirim."
                    else -> "Composer aktif setelah snapshot selesai."
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Pesan untuk host…") },
                enabled = session.canMutate,
                minLines = 1,
                maxLines = 6,
            )
            Button(
                onClick = onSend,
                enabled = session.canMutate && draft.isNotBlank(),
                modifier = Modifier.size(52.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("Kirim")
            }
            if (busy && session.canMutate) {
                FilledTonalButton(
                    onClick = onInterrupt,
                    enabled = !interruptPending,
                    modifier = Modifier.size(52.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Stop")
                }
            }
        }
        if (interruptPending) {
            Text("Menunggu host berhenti…", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Foreground saja. Prompt tanpa ACK: periksa transcript setelah resync sebelum mengirim ulang.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SelectRequestDialog(
    request: CollabUiRequest.Select,
    answered: Boolean,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(request.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                request.helpText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                request.options.forEachIndexed { index, option ->
                    val checked = index in request.checkedIndices
                    val checkedOrDefault = checked || (request.checkedIndices.isEmpty() && index == request.initialIndex)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (request.selectionMarker) {
                            "checkbox" -> Checkbox(checked = checked, onCheckedChange = null, enabled = false)
                            else -> RadioButton(selected = checkedOrDefault, onClick = null, enabled = false)
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(option.label)
                            option.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(
                            onClick = { onSelect(option.label) },
                            enabled = !answered,
                        ) { Text("Pilih") }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun EditorRequestDialog(
    request: CollabUiRequest.Editor,
    answered: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(request.reqId) { mutableStateOf(request.prefill.orEmpty()) }
    BackHandler(enabled = !answered) { onCancel() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !answered,
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = !answered) { Text("Kirim") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !answered) { Text("Batal") }
        },
    )
}

private fun SessionEntry.isRenderable(): Boolean = when (this) {
    is MessageEntry -> message is WireMessage.User || message is WireMessage.Assistant
    is CustomMessageEntry -> customType == "collab-prompt" || display
    is CompactionEntry, is BranchSummaryEntry, is ModelChangeEntry, is ThinkingLevelChangeEntry -> true
    is UnknownEntry -> false
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Idle -> "Status: belum terhubung"
    ConnectionStatus.Connecting -> "Status: menyambungkan"
    is ConnectionStatus.Syncing -> "Status: sinkronisasi ${status.received}/${status.total}"
    ConnectionStatus.Connected -> "Status: tersambung"
    is ConnectionStatus.Reconnecting -> "Status: menyambungkan kembali (${status.attempt})"
    ConnectionStatus.WaitingForNetwork -> "Status: menunggu jaringan"
    ConnectionStatus.Paused -> "Status: dijeda"
    is ConnectionStatus.Ended -> "Status: berakhir"
    is ConnectionStatus.Failed -> "Status: gagal"
}
