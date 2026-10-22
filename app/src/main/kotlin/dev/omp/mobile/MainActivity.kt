package dev.omp.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.data.PairingResult
import dev.omp.mobile.ui.chat.ChatScreen
import dev.omp.mobile.ui.chat.ChatViewModel
import dev.omp.mobile.ui.pairing.PairingScreen
import dev.omp.mobile.ui.sessions.SessionsScreen
import dev.omp.mobile.ui.sessions.SessionsViewModel
import dev.omp.mobile.ui.settings.SettingsScreen
import dev.omp.mobile.ui.settings.SettingsViewModel
import dev.omp.mobile.ui.theme.OmpTheme

private enum class MainTab(val label: String, val icon: Int) {
    SESSIONS("Sessions", R.drawable.ic_sessions),
    CHAT("Chat", R.drawable.ic_chat),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val sessionsViewModel: SessionsViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
            OmpTheme(theme) {
                OmpApp(
                    sessionsViewModel = sessionsViewModel,
                    chatViewModel = chatViewModel,
                    settingsViewModel = settingsViewModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OmpApp(
    sessionsViewModel: SessionsViewModel,
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
) {
    var tabName by rememberSaveable { mutableStateOf(MainTab.SESSIONS.name) }
    val tab = MainTab.valueOf(tabName)
    var showPairing by rememberSaveable { mutableStateOf(false) }
    // Credentials are intentionally not saveable: they never enter a Bundle.
    var pendingCredentials by remember { mutableStateOf<PairingCredentials?>(null) }
    var pendingLabel by rememberSaveable { mutableStateOf("") }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val session by sessionsViewModel.session.collectAsStateWithLifecycle()
    val pairingResult by sessionsViewModel.pairingResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pairingResult) {
        when (val result = pairingResult) {
            is PairingResult.Saved -> {
                saving = false
                pendingCredentials = null
                pendingLabel = ""
                pendingError = null
                showPairing = false
                sessionsViewModel.clearPairingResult()
                tabName = MainTab.CHAT.name
            }

            is PairingResult.Invalid -> {
                saving = false
                pendingError = result.reason
            }

            is PairingResult.StorageError -> {
                saving = false
                pendingError = result.message
            }

            null -> Unit
        }
    }

    LaunchedEffect(session.notice) {
        val notice = session.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        sessionsViewModel.clearNotice()
    }

    if (showPairing) {
        Box(Modifier.fillMaxSize()) {
            PairingScreen(
                onClose = { showPairing = false },
                onParsed = { credentials ->
                    pendingError = null
                    pendingCredentials = credentials
                },
            )
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                ShortNavigationBar {
                    MainTab.values().forEach { item ->
                        ShortNavigationBarItem(
                            selected = tab == item,
                            onClick = { tabName = item.name },
                            icon = {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 * direction }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 10 * direction })
                },
                label = "main-tab",
            ) { currentTab ->
                when (currentTab) {
                    MainTab.SESSIONS -> SessionsScreen(
                        viewModel = sessionsViewModel,
                        onPairRequested = {
                            pendingError = null
                            showPairing = true
                        },
                        onOpenChat = { tabName = MainTab.CHAT.name },
                    )

                    MainTab.CHAT -> ChatScreen(
                        viewModel = chatViewModel,
                        onOpenSessions = { tabName = MainTab.SESSIONS.name },
                    )

                    MainTab.SETTINGS -> SettingsScreen(settingsViewModel)
                }
            }
        }
    }

    val credentials = pendingCredentials
    if (credentials != null) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    pendingCredentials = null
                    pendingError = null
                }
            },
            title = { Text("Save pairing?") },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    Text("Relay: ${credentials.relayOrigin.removePrefix("wss://").removePrefix("ws://")}")
                    Text("Room: ${credentials.roomId}")
                    Text("Access: ${if (credentials.hasWriteToken) "Control" else "View"}")
                    Text(
                        "Credentials will be stored encrypted on this device. An empty label uses the default.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pendingLabel,
                        onValueChange = { pendingLabel = it.take(64) },
                        singleLine = true,
                        label = { Text("Label (optional)") },
                    )
                    pendingError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saving = true
                        sessionsViewModel.pair(
                            credentials,
                            pendingLabel.trim().ifEmpty { null },
                        )
                    },
                    enabled = !saving,
                ) { Text(if (saving) "Saving…" else "Save & connect") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCredentials = null
                        pendingError = null
                    },
                    enabled = !saving,
                ) { Text("Cancel") }
            },
        )
    }
}
