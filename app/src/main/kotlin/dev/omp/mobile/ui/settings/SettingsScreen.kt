package dev.omp.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Personalize your mobile workspace",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Nickname", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used on the next join. An empty value means ${PairingStore.DEFAULT_NICKNAME}.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = edit,
            onValueChange = { edit = it.take(PairingStore.MAX_NICKNAME + 1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Nickname (max ${PairingStore.MAX_NICKNAME})") },
            isError = tooLong,
            shape = MaterialTheme.shapes.extraLarge,
            supportingText = if (tooLong) {
                { Text("Maximum ${PairingStore.MAX_NICKNAME} characters") }
            } else {
                null
            },
        )
        Button(
            onClick = { viewModel.saveNickname(edit.trim()) },
            enabled = changed && !tooLong,
            shape = CircleShape,
        ) { Text("Save nickname") }

        HorizontalDivider()

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        val modes = ThemeMode.values()
        val segmentColors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            activeBorderColor = MaterialTheme.colorScheme.secondary,
            inactiveContainerColor = MaterialTheme.colorScheme.surface,
            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = theme == mode,
                    onClick = { viewModel.saveTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    colors = segmentColors,
                ) {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("OMP Mobile ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text("Wire protocol 3", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Contract baseline: can1357/oh-my-pi @ 0b5c27ab (COLLAB_PROTO 3, 32-byte room key, 16-byte write token).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Wanted connections use a foreground service and reconnect when the network returns. Android battery restrictions, force-stop, or stopping the service can interrupt background connectivity; reopen the app to resume. There is no gateway, offline cache, biometrics, or room discovery.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "The transcript is held in memory; pairings and tokens are stored encrypted in noBackupFilesDir.",
            style = MaterialTheme.typography.bodySmall,
        )
            }
        }
    }
}
