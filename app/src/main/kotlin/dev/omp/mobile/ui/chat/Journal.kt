package dev.omp.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.omp.mobile.collab.ActiveTool
import dev.omp.mobile.collab.BranchSummaryEntry
import dev.omp.mobile.collab.COLLAB_PROMPT_MESSAGE_TYPE
import dev.omp.mobile.collab.CompactionEntry
import dev.omp.mobile.collab.ContentBlock
import dev.omp.mobile.collab.CustomMessageEntry
import dev.omp.mobile.collab.MessageContent
import dev.omp.mobile.collab.MessageEntry
import dev.omp.mobile.collab.ModelChangeEntry
import dev.omp.mobile.collab.SessionEntry
import dev.omp.mobile.collab.ThinkingLevelChangeEntry
import dev.omp.mobile.collab.WireMessage
import dev.omp.mobile.collab.fromParticipant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.Base64

private val PrettyJson = Json { prettyPrint = true }
private val OmpMarkdownComponents = markdownComponents(
    codeBlock = highlightedCodeBlock,
    codeFence = highlightedCodeFence,
)

@Composable
fun JournalRow(
    entry: SessionEntry,
    results: Map<String, WireMessage.ToolResult>,
    active: Map<String, ActiveTool>,
) {
    when (entry) {
        is MessageEntry -> when (val message = entry.message) {
            is WireMessage.User -> UserRow("host", message.content, entry.timestamp)
            is WireMessage.Assistant -> AssistantRow(message, results, active, pending = false)
            is WireMessage.ToolResult -> Unit // rendered through its toolCall pairing
            is WireMessage.Developer -> Unit
        }

        is CustomMessageEntry -> {
            if (entry.customType == COLLAB_PROMPT_MESSAGE_TYPE) {
                UserRow(entry.fromParticipant(), entry.content, entry.timestamp)
            } else if (entry.display) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    AssistChip(onClick = {}, label = { Text(entry.customType) })
                    MessageContentBlock(entry.content)
                }
            }
        }

        is CompactionEntry -> MarkerDivider("Konteks dipadatkan · ${entry.tokensBefore} token")
        is BranchSummaryEntry -> MarkerDivider("Branch summary")
        is ModelChangeEntry -> MarkerText("model → ${entry.model}")
        is ThinkingLevelChangeEntry -> MarkerText("thinking → ${entry.thinkingLevel ?: "off"}")
        else -> Unit
    }
}

@Composable
private fun UserRow(from: String, content: MessageContent, timestamp: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            if (from == "host") "host · $timestamp" else "$from · $timestamp",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MessageContentBlock(content)
    }
}

@Composable
fun AssistantRow(
    message: WireMessage.Assistant,
    results: Map<String, WireMessage.ToolResult>,
    active: Map<String, ActiveTool>,
    pending: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.content.forEach { block ->
            when (block) {
                is ContentBlock.Text -> MarkdownText(block.text)
                is ContentBlock.Thinking -> ThinkingBlock(block.thinking, redacted = false)
                is ContentBlock.RedactedThinking -> ThinkingBlock("", redacted = true)
                is ContentBlock.ToolCall -> {
                    val live = active[block.id]
                    ToolCard(
                        toolCallId = block.id,
                        name = block.name,
                        intent = block.intent ?: live?.intent,
                        args = live?.args ?: block.arguments,
                        partialResult = live?.partialResult,
                        result = results[block.id],
                        running = results[block.id] == null && (live != null || pending),
                    )
                }

                is ContentBlock.Image -> InlineImage(block)
            }
        }
        if (!pending && (message.stopReason == "error" || message.stopReason == "aborted")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(message.stopReason) })
                message.errorMessage?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun MessageContentBlock(content: MessageContent) {
    when (content) {
        is MessageContent.Text -> MarkdownText(content.text)
        is MessageContent.Blocks -> content.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> MarkdownText(block.text)
                is ContentBlock.Image -> InlineImage(block)
                else -> Unit
            }
        }
    }
}

@Composable
fun MarkdownText(text: String) {
    if (text.isBlank()) return
    val state = rememberMarkdownState(text)
    SelectionContainer {
        Markdown(
            markdownState = state,
            components = OmpMarkdownComponents,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThinkingBlock(text: String, redacted: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (redacted) "thinking · redacted" else "thinking")
        }
        if (expanded) {
            Text(
                if (redacted) "(disembunyikan oleh provider)" else text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun InlineImage(block: ContentBlock.Image) {
    val bitmap by produceState<Bitmap?>(initialValue = null, block.data) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(block.data) }
    }
    val image = bitmap
    if (image == null) {
        Text("Gambar tidak dapat didekode", style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Lampiran gambar $block.mimeType",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(180.dp)
                .fillMaxWidth(),
        )
    }
}

private fun decodeThumbnail(base64: String): Bitmap? {
    return runCatching {
        val bytes = Base64.getDecoder().decode(base64)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

@Composable
fun ToolCard(
    toolCallId: String,
    name: String,
    intent: String?,
    args: JsonElement?,
    partialResult: JsonElement?,
    result: WireMessage.ToolResult?,
    running: Boolean,
) {
    var expanded by remember(toolCallId) { mutableStateOf(false) }
    val status = when {
        result?.isError == true -> "error"
        result != null -> "success"
        running -> "running"
        else -> "selesai"
    }
    val output = result?.content?.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Image -> "[gambar ${block.mimeType}]"
            else -> ""
        }
    }.orEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name · ${toolCallId.take(8)}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        "error" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!intent.isNullOrBlank()) {
                Text(intent, style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                args?.let {
                    Text("args", style = MaterialTheme.typography.labelSmall)
                    Text(
                        runCatching { PrettyJson.encodeToString(JsonElement.serializer(), it) }
                            .getOrDefault(it.toString()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val partial = partialResult?.toString()
                if (result == null && partial != null) {
                    Text("output (berjalan)", style = MaterialTheme.typography.labelSmall)
                    Text(partial, style = MaterialTheme.typography.bodySmall)
                }
                if (output.isNotBlank()) {
                    Text(if (result?.isError == true) "error" else "output", style = MaterialTheme.typography.labelSmall)
                    Text(output, style = MaterialTheme.typography.bodySmall)
                }
            } else if (result != null || partialResult != null) {
                Text("Ketuk untuk detail", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MarkerDivider(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun MarkerText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Basename of the host cwd, display only. */
fun cwdBasename(cwd: String?): String? {
    if (cwd.isNullOrBlank()) return null
    return File(cwd).name.ifBlank { cwd }
}
