package dev.omp.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omp.mobile.R
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.ContextUsage
import dev.omp.mobile.collab.SessionState
import dev.omp.mobile.collab.WireModel
import dev.omp.mobile.data.CollabSessionState
import java.util.Locale

/** Instrumentation hooks for the native chat header and its details surface. */
internal object ChatHeaderTags {
    const val Back = "chat_header_back"
    const val Details = "chat_header_details"
    const val Status = "chat_header_status"
    const val Title = "chat_header_title"
    const val Metadata = "chat_header_metadata"
    const val DetailsDialog = "chat_header_details_dialog"
    const val DetailsClose = "chat_header_details_close"
    const val ComposerDetails = "chat_composer_details"
}

/**
 * Compact app bar for an active collab session.
 *
 * The title is truncated to one line; the single metadata line covers project,
 * model, context and queue. Full values and the connection/lifecycle caveats
 * live behind the real details action so the transcript keeps the vertical
 * space it needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHeaderBar(
    session: CollabSessionState,
    onOpenSessions: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val state = session.replica.state
    val title = sessionTitle(session)
    val meta = metadataLine(state)
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(ChatHeaderTags.Title),
                    )
                    if (meta != null) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ChatHeaderTags.Metadata),
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onOpenSessions,
                    modifier = Modifier.size(48.dp).testTag(ChatHeaderTags.Back),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_header_back),
                        contentDescription = "Back to sessions",
                    )
                }
            },
            actions = {
                if (session.status == ConnectionStatus.Connected) {
                    SessionStatusBadge(session)
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(
                    onClick = onOpenDetails,
                    modifier = Modifier.size(48.dp).testTag(ChatHeaderTags.Details),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_header_info),
                        contentDescription = "Session details",
                    )
                }
            },
            // MainActivity's Scaffold already applies the status-bar inset.
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SessionStatusBadge(session: CollabSessionState) {
    val replica = session.replica
    val needsInput = replica.uiRequest != null || replica.pendingUiRequests > 0
    val working = replica.working || replica.state?.isStreaming == true
    val label = when {
        needsInput -> "Needs input"
        working -> "Working"
        else -> "Live"
    }
    val container = if (needsInput) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (needsInput) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.testTag(ChatHeaderTags.Status),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(
                Modifier
                    .size(6.dp)
                    .background(content, CircleShape),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * Read-only inspection of the session context that no longer fits in the bar.
 * Nothing here is an editor: it only reports what the host published.
 */
@Composable
internal fun SessionDetailsDialog(
    session: CollabSessionState,
    onDismiss: () -> Unit,
) {
    val state = session.replica.state
    val title = sessionTitle(session)
    val model = modelLabel(state?.model)
    val modelDetail = listOfNotNull(
        state?.model?.provider?.takeIf { it.isNotBlank() },
        model,
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    val context = contextLabel(state?.contextUsage)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session details") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag(ChatHeaderTags.DetailsDialog),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailRow("Session", title)
                DetailRow("Access", accessLabel(session))
                Text(
                    accessExplanation(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailRow("Connection", connectionStatusText(session.status))
                session.relayHost
                    ?.removePrefix("wss://")
                    ?.removePrefix("ws://")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DetailRow("Relay", it) }
                session.pairingLabel
                    ?.takeIf { it.isNotBlank() && it != title }
                    ?.let { DetailRow("Pairing", it) }
                state?.cwd?.takeIf { it.isNotBlank() }?.let { DetailRow("Project", it) }
                modelDetail?.let { DetailRow("Model", it) }
                context?.let { DetailRow("Context", it) }
                state?.thinkingLevel
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DetailRow("Thinking", it) }
                state?.queuedMessageCount
                    ?.takeIf { it > 0 }
                    ?.let { DetailRow("Queued prompts", it.toString()) }
                session.storageError
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DetailRow("Storage", it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DetailRow(
                    "Lifecycle",
                    "The app connects only while it is in the foreground. Returning from the " +
                        "background re-syncs the transcript; the host stays authoritative.",
                )
                DetailRow(
                    "Delivery",
                    "Prompt sends have no delivery ACK. If the connection drops mid-send, check " +
                        "the transcript after re-sync before sending again.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag(ChatHeaderTags.DetailsClose),
            ) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun sessionTitle(session: CollabSessionState): String =
    session.replica.header?.title?.takeIf { it.isNotBlank() }
        ?: session.replica.state?.sessionName?.takeIf { it.isNotBlank() }
        ?: session.pairingLabel?.takeIf { it.isNotBlank() }
        ?: "Chat"

internal fun connectionStatusText(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connecting -> "Connecting…"
    is ConnectionStatus.Syncing -> "Syncing ${status.received}/${status.total}…"
    ConnectionStatus.Connected -> "Connected"
    is ConnectionStatus.Reconnecting -> "Reconnecting (attempt ${status.attempt})…"
    ConnectionStatus.WaitingForNetwork -> "Waiting for network…"
    ConnectionStatus.Paused -> "Paused while the app is in the background. Reopen it to reconnect."
    ConnectionStatus.Idle -> "Not connected."
    is ConnectionStatus.Ended -> "Session ended: ${status.reason}"
    is ConnectionStatus.Failed -> "Failed: ${status.message}"
}

/** One compact line: project, model, context usage, queued prompts. */
private fun metadataLine(state: SessionState?): String? {
    val parts = buildList {
        cwdBasename(state?.cwd)?.let { add(it) }
        modelLabel(state?.model)?.let { add(it) }
        state?.contextUsage?.percent?.let { add(String.format(Locale.US, "ctx %.0f%%", it)) }
        state?.queuedMessageCount?.takeIf { it > 0 }?.let { add("$it queued") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun modelLabel(model: WireModel?): String? {
    if (model == null) return null
    return model.name.ifBlank { model.id }.takeIf { it.isNotBlank() }
}

private fun contextLabel(usage: ContextUsage?): String? {
    if (usage == null) return null
    val percent = usage.percent?.let { String.format(Locale.US, "%.0f%%", it) }
    val tokens = usage.tokens?.let(::formatTokens)
    val window = usage.contextWindow?.let(::formatTokens)
    return when {
        tokens != null && window != null -> "$tokens / $window" + (percent?.let { " · $it" } ?: "")
        percent != null -> percent
        tokens != null -> tokens
        else -> null
    }
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun accessLabel(session: CollabSessionState): String =
    if (session.readOnly || !session.hasWriteToken) "View" else "Control"

private fun accessExplanation(session: CollabSessionState): String = when {
    session.readOnly ->
        "Read-only transcript. Prompts, attachments, stop, and agent controls are disabled " +
            "for this pairing."
    !session.hasWriteToken ->
        "This device paired without a write token, so it can read the transcript but cannot " +
            "send or control."
    session.status != ConnectionStatus.Connected ->
        "Control is available, but sending unlocks once the session is connected."
    else ->
        "Control session: prompts, attachments, stop, and subagent commands are available."
}
