package dev.omp.mobile.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                Text("Sessions", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pairing tersimpan di perangkat ini; daftar ini bukan klaim host aktif.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onPairRequested) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Pair")
            }
        }

        session.storageError?.let { error ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Kredensial tidak dapat dibuka", style = MaterialTheme.typography.titleSmall)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (pairings.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Belum ada pairing")
                    Text(
                        "Pindai QR atau tempel link dari host OMP.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onPairRequested) { Text("Pindai QR / tempel link") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pairings, key = { it.id }) { pairing ->
                    val selected = session.pairingId == pairing.id
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
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
                            Text(pairing.relayHost, style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (selected) "Pairing terpilih" else "Pairing tersimpan",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.select(pairing.id)
                                        onOpenChat()
                                    },
                                    enabled = !selected,
                                ) { Text(if (selected) "Aktif" else "Buka") }
                                TextButton(onClick = { renameTarget = pairing }) { Text("Ganti label") }
                                TextButton(onClick = { forgetTarget = pairing }) { Text("Hapus lokal") }
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
            title = { Text("Ganti label") },
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
                ) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Batal") }
            },
        )
    }

    forgetTarget?.let { pairing ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Hapus pairing lokal?") },
            text = { Text("Socket ditutup dan kredensial dihapus. Host tidak dihentikan.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(pairing.id)
                        forgetTarget = null
                    },
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("Batal") }
            },
        )
    }
}
