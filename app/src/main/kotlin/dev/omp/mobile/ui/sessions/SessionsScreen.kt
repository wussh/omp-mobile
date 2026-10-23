package dev.omp.mobile.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import dev.omp.mobile.collab.ConnectionStatus
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
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    if (pairings.isEmpty()) "Connect to your OMP host"
                    else "${pairings.size} saved · One connection at a time",
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
                    var menuExpanded by remember(pairing.id) { mutableStateOf(false) }
                    Card(
                        onClick = {
                            if (!selected) viewModel.select(pairing.id)
                            onOpenChat()
                        },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.ic_sessions),
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pairing.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        pairing.relayHost,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            painterResource(R.drawable.ic_more_vert),
                                            contentDescription = "Pairing options for ${pairing.label}",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = { menuExpanded = false; renameTarget = pairing },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Remove") },
                                            onClick = { menuExpanded = false; forgetTarget = pairing },
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        pairing.accessLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    if (selected) sessionListStatus(session.status) else "Saved · Tap to open",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                "Room ${pairing.roomId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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

private fun sessionListStatus(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "Connected · Tap to return"
    ConnectionStatus.Connecting -> "Connecting"
    is ConnectionStatus.Syncing -> "Syncing"
    is ConnectionStatus.Reconnecting -> "Reconnecting"
    ConnectionStatus.WaitingForNetwork -> "Waiting for network"
    ConnectionStatus.Paused -> "Paused"
    is ConnectionStatus.Failed -> "Connection failed"
    is ConnectionStatus.Ended -> "Session ended"
    ConnectionStatus.Idle -> "Selected · Tap to open"
}
