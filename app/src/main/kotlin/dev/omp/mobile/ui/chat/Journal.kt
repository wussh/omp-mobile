package dev.omp.mobile.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.omp.mobile.R
import dev.omp.mobile.collab.ADVISOR_MESSAGE_TYPE
import dev.omp.mobile.collab.ASYNC_RESULT_MESSAGE_TYPE
import dev.omp.mobile.collab.ActiveTool
import dev.omp.mobile.collab.AdvisorNoteView
import dev.omp.mobile.collab.AdvisorSeverity
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
import dev.omp.mobile.collab.advisorCard
import dev.omp.mobile.collab.asyncResultText
import dev.omp.mobile.collab.fromParticipant
import dev.omp.mobile.collab.messageContentText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Base64

private val PrettyJson = Json { prettyPrint = true }
private val OmpMarkdownComponents = markdownComponents(
    codeBlock = { model ->
        MarkdownCodeBlock(content = model.content, node = model.node, style = model.typography.code) { code, language, style ->
            NativeCodeBlock(code = code, language = language, style = style)
        }
    },
    codeFence = { model ->
        MarkdownCodeFence(content = model.content, node = model.node, style = model.typography.code) { code, language, style ->
            NativeCodeBlock(code = code, language = language, style = style)
        }
    },
)

@Composable
fun JournalRow(
    entry: SessionEntry,
    results: Map<String, WireMessage.ToolResult>,
    active: Map<String, ActiveTool>,
    latestTodoCallId: String?,
) {
    when (entry) {
        is MessageEntry -> when (val message = entry.message) {
            is WireMessage.User -> UserRow("host", message.content, entry.timestamp)
            is WireMessage.Assistant -> AssistantRow(message, results, active, pending = false, latestTodoCallId = latestTodoCallId)
            is WireMessage.ToolResult -> Unit // rendered through its toolCall pairing
            is WireMessage.Developer, is WireMessage.Unknown -> Unit
        }

        is CustomMessageEntry -> {
            if (entry.customType == COLLAB_PROMPT_MESSAGE_TYPE) {
                UserRow(entry.fromParticipant(), entry.content, entry.timestamp)
            } else if (entry.display) {
                CustomMessageCard(entry)
            }
        }

        is CompactionEntry -> MarkerDivider("Context compacted · ${entry.tokensBefore} tokens")
        is BranchSummaryEntry -> MarkerDivider("Branch summary")
        is ModelChangeEntry -> MarkerText("model → ${entry.model}")
        is ThinkingLevelChangeEntry -> MarkerText("thinking → ${entry.thinkingLevel ?: "off"}")
        else -> Unit
    }
}

@Composable
private fun CustomMessageCard(entry: CustomMessageEntry) {
    if (entry.customType == ADVISOR_MESSAGE_TYPE) {
        AdvisorCard(entry)
    } else {
        GenericCustomMessageCard(entry)
    }
}

@Composable
private fun AdvisorCard(entry: CustomMessageEntry) {
    val model = remember(entry.details, entry.content) { advisorCard(entry.details, entry.content) }
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val expandable = model.notes.isNotEmpty()
    val meta = buildString {
        append(model.notes.size)
        append(if (model.notes.size == 1) " note" else " notes")
        if (model.blockerCount > 0) {
            append(" · ")
            append(model.blockerCount)
            append(if (model.blockerCount == 1) " blocker" else " blockers")
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.largeIncreased,
    ) {
        Column(
            Modifier.padding(12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExpandableCardHeader(
                iconRes = R.drawable.ic_advisor,
                title = "Advisor",
                meta = if (expandable) meta else "No notes",
                expanded = expanded,
                expandable = expandable,
                onToggle = { expanded = !expanded },
            )
            if (!expandable) {
                Text(
                    "The host sent an advisor update without note text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        model.notes.forEachIndexed { index, note ->
                            if (index > 0) HorizontalDivider()
                            AdvisorNoteRow(note)
                        }
                    }
                }
                if (!expanded) {
                    model.notes.firstOrNull()?.text?.let { note ->
                        Text(
                            note.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 30.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvisorNoteRow(note: AdvisorNoteView) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            note.severityLabel?.let { label -> SeverityPill(label = label, severity = note.severity) }
            note.advisor?.takeIf { it != "default" }?.let { advisor ->
                Text(
                    advisor,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            note.turnsAgo?.let { ago ->
                Text(
                    "T-$ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MarkdownText(note.text)
    }
}

@Composable
private fun SeverityPill(label: String, severity: AdvisorSeverity?) {
    val colors = MaterialTheme.colorScheme
    val container = when (severity) {
        AdvisorSeverity.Blocker -> colors.errorContainer
        AdvisorSeverity.Concern -> colors.tertiaryContainer
        AdvisorSeverity.Nit, null -> colors.surfaceContainerHighest
    }
    val content = when (severity) {
        AdvisorSeverity.Blocker -> colors.onErrorContainer
        AdvisorSeverity.Concern -> colors.onTertiaryContainer
        AdvisorSeverity.Nit, null -> colors.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(color = container, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun GenericCustomMessageCard(entry: CustomMessageEntry) {
    val asyncResult = entry.customType == ASYNC_RESULT_MESSAGE_TYPE
    val body = remember(entry.content, asyncResult) {
        val text = messageContentText(entry.content)
        if (asyncResult) asyncResultText(text) else text
    }
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val expandable = when (val content = entry.content) {
        is MessageContent.Text -> body.isNotBlank()
        is MessageContent.Blocks -> body.isNotBlank() || content.blocks.any { it is ContentBlock.Image }
    }
    val preview = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val title = entry.customType
        .split('-', '_')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { "Custom message" }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.largeIncreased,
    ) {
        Column(
            Modifier.padding(12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExpandableCardHeader(
                iconRes = R.drawable.ic_chat,
                title = title,
                meta = null,
                expanded = expanded,
                expandable = expandable,
                onToggle = { expanded = !expanded },
            )
            if (!expandable) {
                Text(
                    "The host sent this message without displayable text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    if (asyncResult) {
                        AsyncResultContent(entry.content)
                    } else {
                        MessageContentBlock(entry.content)
                    }
                }
                if (!expanded) {
                    Text(
                        preview.ifBlank { "Attachment" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 30.dp),
                    )
                }
            }
        }
    }
}

/** Async output is preformatted text, not Markdown: HTML-like job output must stay visible. */
@Composable
private fun AsyncResultContent(content: MessageContent) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is MessageContent.Text -> AsyncResultText(content.text)
            is MessageContent.Blocks -> content.blocks.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> AsyncResultText(block.text)
                    is ContentBlock.Image -> InlineImage(block)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun AsyncResultText(text: String) {
    val output = remember(text) { asyncResultText(text) }
    SelectionContainer {
        Text(
            output,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ExpandableCardHeader(
    iconRes: Int,
    title: String,
    meta: String?,
    expanded: Boolean,
    expandable: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "cardChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = expandable,
                onClickLabel = if (expandable) {
                    if (expanded) "Collapse" else "Expand"
                } else {
                    null
                },
                role = if (expandable) Role.Button else null,
                onClick = onToggle,
            )
            .semantics {
                if (expandable) stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            meta?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expandable) {
            Icon(
                painter = painterResource(R.drawable.ic_advisor_chevron),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).rotate(rotation),
            )
        }
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
    latestTodoCallId: String?,
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
                    val result = results[block.id]
                    val staleTodo = block.name == "todo" &&
                        result != null &&
                        !result.isError &&
                        latestTodoCallId != null &&
                        block.id != latestTodoCallId
                    if (!staleTodo) {
                        val live = active[block.id]
                        ToolCard(
                            toolCallId = block.id,
                            name = block.name,
                            intent = block.intent ?: live?.intent,
                            args = live?.args ?: block.arguments,
                            partialResult = live?.partialResult,
                            result = result,
                            running = result == null && (live != null || pending),
                        )
                    }
                }

                is ContentBlock.Image -> InlineImage(block)
            }
        }
        if (!pending && (message.stopReason == "error" || message.stopReason == "aborted")) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Stopped · ${message.stopReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
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
private fun NativeCodeBlock(code: String, language: String?, style: TextStyle) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlighted by produceState(initialValue = AnnotatedString(code), code, language, dark) {
        value = withContext(Dispatchers.Default) {
            highlightCode(code = code, language = language, dark = dark)
        }
    }
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val languageLabel = language?.trim()?.takeIf { it.isNotEmpty() } ?: "Code"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                languageLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (copied) {
                Text(
                    "Copied",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Code", code))
                    copied = true
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = if (copied) "Code copied" else "Copy code",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = highlighted,
            style = style.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(end = 8.dp),
        )
    }
}

private fun highlightCode(code: String, language: String?, dark: Boolean): AnnotatedString {
    val syntax = syntaxLanguageFor(language) ?: return AnnotatedString(code)
    val builder = Highlights.Builder()
        .theme(SyntaxThemes.default(darkMode = dark))
        .code(code)
    val highlights = builder.language(syntax).build().getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            val span = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(span, highlight.location.start, highlight.location.end)
        }
    }
}

private fun syntaxLanguageFor(language: String?): SyntaxLanguage? {
    val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return SyntaxLanguage.getByName(normalized) ?: when (normalized) {
        "kt", "kts" -> SyntaxLanguage.KOTLIN
        "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
        "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
        "sh", "zsh", "console" -> SyntaxLanguage.SHELL
        "py" -> SyntaxLanguage.PYTHON
        "rs" -> SyntaxLanguage.RUST
        "c++", "cc", "hpp", "hxx" -> SyntaxLanguage.CPP
        "c#" -> SyntaxLanguage.CSHARP
        "golang" -> SyntaxLanguage.GO
        "rb" -> SyntaxLanguage.RUBY
        "pl" -> SyntaxLanguage.PERL
        "coffee" -> SyntaxLanguage.COFFEESCRIPT
        else -> null
    }
}

@Composable
private fun ThinkingBlock(text: String, redacted: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (redacted) "thinking · redacted" else "thinking")
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically(),
        ) {
            Text(
                if (redacted) "(redacted by provider)" else text,
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
        Text("Image could not be decoded", style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Image attachment $block.mimeType",
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

enum class TodoTaskStatus(val label: String, val symbol: String) {
    Pending("Pending", "○"),
    InProgress("In progress", "→"),
    Completed("Completed", "✓"),
    Abandoned("Abandoned", "✕"),
    Blocked("Blocked", "!"),
}

data class TodoTaskView(
    val content: String,
    val status: TodoTaskStatus,
    val blocker: String?,
)

data class TodoPhaseView(
    val name: String,
    val tasks: List<TodoTaskView>,
)

/**
 * Parses the canonical `todo` tool-result details from can1357/oh-my-pi @
 * 0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0:
 * `{ op, phases: [{ name, tasks: [{ content, status, blocker? }] }] }`.
 * Returns null when [details] does not carry a phases array (legacy or non-todo results).
 */
fun parseTodoPhases(details: JsonElement?): List<TodoPhaseView>? {
    val root = details as? JsonObject ?: return null
    val phases = root["phases"] as? JsonArray ?: return null
    return phases.mapNotNull phaseLoop@{ element ->
        val phase = element as? JsonObject ?: return@phaseLoop null
        val name = phase.string("name") ?: return@phaseLoop null
        val tasks = (phase["tasks"] as? JsonArray)?.mapNotNull taskLoop@{ taskElement ->
            val task = taskElement as? JsonObject ?: return@taskLoop null
            val content = task.string("content") ?: return@taskLoop null
            val status = when (task.string("status")) {
                "pending" -> TodoTaskStatus.Pending
                "in_progress" -> TodoTaskStatus.InProgress
                "completed" -> TodoTaskStatus.Completed
                "abandoned" -> TodoTaskStatus.Abandoned
                "blocked" -> TodoTaskStatus.Blocked
                else -> return@taskLoop null
            }
            TodoTaskView(
                content = content,
                status = status,
                blocker = task.string("blocker")?.takeIf { it.isNotBlank() },
            )
        }.orEmpty()
        TodoPhaseView(name = name, tasks = tasks)
    }
}

/** Latest successful `todo` result carrying canonical phases; failed updates do not replace visible state. */
fun latestTodoResult(results: Map<String, WireMessage.ToolResult>): WireMessage.ToolResult? =
    results.values.asSequence()
        .filter { it.toolName == "todo" && !it.isError && (it.details as? JsonObject)?.get("phases") is JsonArray }
        .maxByOrNull { it.timestamp }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

@Composable
private fun TodoChecklistCard(
    toolCallId: String,
    phases: List<TodoPhaseView>,
    details: JsonElement?,
) {
    var expanded by remember(toolCallId) { mutableStateOf(true) }
    val total = phases.sumOf { it.tasks.size }
    val completed = phases.sumOf { phase -> phase.tasks.count { it.status == TodoTaskStatus.Completed } }
    val abandoned = phases.sumOf { phase -> phase.tasks.count { it.status == TodoTaskStatus.Abandoned } }
    val blocked = phases.sumOf { phase -> phase.tasks.count { it.status == TodoTaskStatus.Blocked } }
    val summary = if (total == 0) {
        "No tasks"
    } else {
        buildString {
            append("$completed/$total done")
            if (abandoned > 0) append(" · $abandoned dropped")
            if (blocked > 0) append(" · $blocked blocked")
        }
    }
    val op = (details as? JsonObject)?.string("op")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.largeIncreased,
    ) {
        Column(
            Modifier.padding(12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (op.isNullOrBlank()) "todo" else "todo · $op",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blocked > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (expanded) {
                phases.forEachIndexed { index, phase ->
                    val phaseDone = phase.tasks.count { it.status == TodoTaskStatus.Completed }
                    Text(
                        "${index + 1}. ${phase.name} · $phaseDone/${phase.tasks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (index == 0) 2.dp else 6.dp),
                    )
                    phase.tasks.forEach { task -> TodoTaskRow(task) }
                }
                if (phases.isEmpty()) {
                    Text(
                        "The host has not recorded any tasks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Tap for details", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TodoTaskRow(task: TodoTaskView) {
    val colors = MaterialTheme.colorScheme
    val accent = when (task.status) {
        TodoTaskStatus.Pending -> colors.onSurfaceVariant
        TodoTaskStatus.InProgress -> colors.primary
        TodoTaskStatus.Completed -> colors.primary
        TodoTaskStatus.Abandoned -> colors.onSurfaceVariant
        TodoTaskStatus.Blocked -> colors.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(task.status.label)
                    append(": ")
                    append(task.content)
                    task.blocker?.let {
                        append(" — blocked: ")
                        append(it)
                    }
                }
            }
            .padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            task.status.symbol,
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                task.content,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == TodoTaskStatus.Completed || task.status == TodoTaskStatus.Abandoned) {
                    colors.onSurfaceVariant
                } else {
                    colors.onSurface
                },
                textDecoration = if (task.status == TodoTaskStatus.Abandoned) TextDecoration.LineThrough else null,
            )
            task.blocker?.let {
                Text(
                    "blocked: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                )
            }
        }
    }
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
    val todoPhases = remember(name, result) {
        if (name == "todo" && result?.isError != true) parseTodoPhases(result?.details) else null
    }
    if (todoPhases != null) {
        TodoChecklistCard(toolCallId = toolCallId, phases = todoPhases, details = result?.details)
        return
    }
    var expanded by remember(toolCallId) { mutableStateOf(false) }
    val status = when {
        result?.isError == true -> "error"
        result != null -> "success"
        running -> "running"
        else -> "done"
    }
    val output = result?.content?.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Image -> "[image ${block.mimeType}]"
            else -> ""
        }
    }.orEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.largeIncreased,
    ) {
        Column(
            Modifier.padding(12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
                    Text("output (running)", style = MaterialTheme.typography.labelSmall)
                    Text(partial, style = MaterialTheme.typography.bodySmall)
                }
                if (output.isNotBlank()) {
                    Text(if (result?.isError == true) "error" else "output", style = MaterialTheme.typography.labelSmall)
                    Text(output, style = MaterialTheme.typography.bodySmall)
                }
            } else if (result != null || partialResult != null) {
                Text("Tap for details", style = MaterialTheme.typography.labelSmall)
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
