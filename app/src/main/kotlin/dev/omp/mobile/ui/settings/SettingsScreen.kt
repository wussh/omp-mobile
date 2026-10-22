package dev.omp.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omp.mobile.BuildConfig
import dev.omp.mobile.data.PairingStore
import dev.omp.mobile.data.ThemeMode

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var edit by remember(nickname) { mutableStateOf(nickname) }
    val tooLong = edit.trim().length > PairingStore.MAX_NICKNAME
    val changed = edit.trim().ifEmpty { PairingStore.DEFAULT_NICKNAME } != nickname

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Nickname", style = MaterialTheme.typography.titleMedium)
        Text(
            "Dipakai saat join berikutnya. Kosong berarti ${PairingStore.DEFAULT_NICKNAME}.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = edit,
            onValueChange = { edit = it.take(PairingStore.MAX_NICKNAME + 1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Nickname (maks ${PairingStore.MAX_NICKNAME})") },
            isError = tooLong,
            supportingText = if (tooLong) {
                { Text("Maksimum ${PairingStore.MAX_NICKNAME} karakter") }
            } else {
                null
            },
        )
        Button(
            onClick = { viewModel.saveNickname(edit.trim()) },
            enabled = changed && !tooLong,
        ) { Text("Simpan nickname") }

        HorizontalDivider()

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        ThemeMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = theme == mode, onClick = { viewModel.saveTheme(mode) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == mode, onClick = null)
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        HorizontalDivider()

        Text("Tentang", style = MaterialTheme.typography.titleMedium)
        Text("OMP Mobile ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text("Wire protocol 3", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Baseline kontrak: can1357/oh-my-pi @ 0b5c27ab (COLLAB_PROTO 3, room key 32 byte, write token 16 byte).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Koneksi hanya berjalan saat aplikasi di foreground. Tidak ada background service, gateway, cache offline, biometric, atau discovery room.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Transcript disimpan di memori; pairing dan token disimpan terenkripsi di noBackupFilesDir.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
