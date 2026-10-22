package dev.omp.mobile.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omp.mobile.R
import dev.omp.mobile.data.SavedPairing

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onPairRequested: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val pairings by viewModel.pairings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<SavedPairing?>(null) }
    var forgetTarget by remember { mutableStateOf<SavedPairing?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    "Pairings are stored on this device; this list is not a claim that the host is active.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onPairRequested,
                shape = CircleShape,
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Pair")
            }
        }

        AnimatedVisibility(
            visible = session.storageError != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val error = session.storageError.orEmpty()
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Credentials could not be opened", style = MaterialTheme.typography.titleSmall)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (pairings.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLargeIncreased,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No pairings yet")
                    Text(
                        "Scan a QR code or paste a link from the OMP host.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onPairRequested, shape = CircleShape) { Text("Scan QR / paste link") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pairings, key = { it.id }) { pairing ->
                    val selected = session.pairingId == pairing.id
                    Card(
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        shape = MaterialTheme.shapes.largeIncreased,
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pairing.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                AssistChip(
                                    onClick = {},
                                    label = { Text(pairing.accessLabel) },
                                )
                            }
                            Text("${pairing.relayHost} · room ${pairing.roomId}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (selected) "Selected pairing" else "Saved pairing",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.select(pairing.id)
                                        onOpenChat()
                                    },
                                    enabled = !selected,
                                    shape = CircleShape,
                                ) { Text(if (selected) "Active" else "Open") }
                                TextButton(onClick = { renameTarget = pairing }) { Text("Rename") }
                                TextButton(onClick = { forgetTarget = pairing }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { pairing ->
        var label by remember(pairing.id) { mutableStateOf(pairing.label) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename pairing") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (label.isBlank()) return@TextButton
                        viewModel.rename(pairing.id, label)
                        renameTarget = null
                    },
                    enabled = label.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    forgetTarget?.let { pairing ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Delete local pairing?") },
            text = { Text("The socket is closed and credentials are deleted. The host keeps running.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(pairing.id)
                        forgetTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("Cancel") }
            },
        )
    }
}
