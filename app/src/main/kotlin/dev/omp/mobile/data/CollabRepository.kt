package dev.omp.mobile.data

import dev.omp.mobile.collab.AgentCommand
import dev.omp.mobile.collab.CollabClient
import dev.omp.mobile.collab.ConnectionStatus
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.PromptImage
import dev.omp.mobile.collab.PairingParseResult
import dev.omp.mobile.collab.ReplicaSnapshot
import dev.omp.mobile.collab.SendResult
import dev.omp.mobile.collab.TranscriptResult
import dev.omp.mobile.collab.parseCollabLink
import dev.omp.mobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PairingResult {
    data class Saved(val id: String) : PairingResult
    data class Invalid(val reason: String) : PairingResult
    data class StorageError(val message: String = CREDENTIAL_RECOVERY_MESSAGE) : PairingResult
}

/** Everything the UI needs, with credentials stripped. */
data class CollabSessionState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val replica: ReplicaSnapshot = ReplicaSnapshot(),
    val readOnly: Boolean = false,
    val hasWriteToken: Boolean = false,
    val pairingId: String? = null,
    val pairingLabel: String? = null,
    val relayHost: String? = null,
    val storageError: String? = null,
    val notice: String? = null,
) {
    val canMutate: Boolean
        get() = status is ConnectionStatus.Connected && hasWriteToken && !readOnly
}

interface CollabRepository {
    val pairings: StateFlow<List<SavedPairing>>
    val session: StateFlow<CollabSessionState>
    val nickname: StateFlow<String>
    val theme: StateFlow<ThemeMode>

    suspend fun pair(rawLink: String, label: String?): PairingResult
    suspend fun select(id: String)
    suspend fun rename(id: String, label: String)
    suspend fun forget(id: String)
    fun retry()
    fun disconnect()
    suspend fun sendPrompt(text: String, images: List<PromptImage> = emptyList()): SendResult
    suspend fun interrupt(): SendResult
    suspend fun respond(reqId: Long, value: String?): SendResult
    suspend fun sendAgentCommand(command: AgentCommand, agentId: String, text: String? = null): SendResult
    suspend fun fetchTranscript(agentId: String, fromByte: Long): TranscriptResult
    suspend fun setNickname(value: String)
    suspend fun setTheme(mode: ThemeMode)
    fun clearNotice()
}

interface SessionRuntimeHost {
    fun onForeground(networkReady: Boolean)
    fun onBackground()
    fun onNetworkUnavailable()
    fun onNetworkAvailable(changed: Boolean)
    fun onNetworkValidated()
}

@Singleton
class DefaultCollabRepository @Inject constructor(
    private val store: PairingStore,
    private val lifecycle: AndroidSessionLifecycle,
) : CollabRepository, SessionRuntimeHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = CollabClient(scope)

    private val _pairings = MutableStateFlow<List<SavedPairing>>(emptyList())
    override val pairings: StateFlow<List<SavedPairing>> = _pairings.asStateFlow()

    private val _session = MutableStateFlow(CollabSessionState())
    override val session: StateFlow<CollabSessionState> = _session.asStateFlow()

    private val _nickname = MutableStateFlow(PairingStore.DEFAULT_NICKNAME)
    override val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    override val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private var stored: List<StoredPairing> = emptyList()
    private var selectedId: String? = null
    private var autoConnectWanted = false
    private var foreground = false
    private var networkReady = false
    private var restoreDone = false
    private var storageError: String? = null
    private var notice: String? = null

    init {
        lifecycle.attach(this)
        scope.launch {
            store.storedPairingsFlow.collect { result -> applyStored(result) }
        }
        scope.launch {
            _nickname.value = store.nickname.first()
            _theme.value = store.theme.first()
            val last = store.lastPairingId.first()
            val storedResult = store.storedPairings()
            if (storedResult is StoreResult.Ok) {
                stored = storedResult.value
                _pairings.value = stored.map(StoredPairing::toSaved)
                if (last != null) {
                    val target = stored.firstOrNull { it.id == last }
                    if (target != null) {
                        selectedId = target.id
                        autoConnectWanted = true
                    } else {
                        // Stale restore id: drop it and show Sessions.
                        store.setLastPairingId(null)
                        selectedId = null
                        autoConnectWanted = false
                    }
                }
            }
            restoreDone = true
            publishSession()
            if (autoConnectWanted) maybeConnect()
        }
        scope.launch {
            client.state.collect { state ->
                publishSession(state)
                when (val status = state.status) {
                    is ConnectionStatus.Ended, is ConnectionStatus.Failed, ConnectionStatus.Idle -> {
                        if (autoConnectWanted) {
                            autoConnectWanted = false
                            store.setLastPairingId(null)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun applyStored(result: StoreResult<List<StoredPairing>>) {
        when (result) {
            is StoreResult.Ok -> {
                storageError = null
                stored = result.value
                _pairings.value = stored.map(StoredPairing::toSaved)
                if (selectedId != null && stored.none { it.id == selectedId }) {
                    selectedId = null
                    autoConnectWanted = false
                    client.clearSession()
                    scope.launch { store.setLastPairingId(null) }
                }
            }

            is StoreResult.Error -> {
                storageError = result.message
            }
        }
        publishSession()
    }

    private fun publishSession(state: dev.omp.mobile.collab.ClientState = client.state.value) {
        val selected = stored.firstOrNull { it.id == selectedId }
        _session.value = CollabSessionState(
            status = state.status,
            replica = state.replica,
            readOnly = state.readOnly,
            hasWriteToken = state.hasWriteToken,
            pairingId = selectedId,
            pairingLabel = selected?.label,
            relayHost = selected?.relayOrigin?.removePrefix("wss://")?.removePrefix("ws://"),
            storageError = storageError,
            notice = notice,
        )
    }

    private fun publishNotice(message: String?) {
        notice = message
        publishSession()
    }

    override fun clearNotice() {
        publishNotice(null)
    }

    override suspend fun pair(rawLink: String, label: String?): PairingResult {
        val parsed = parseCollabLink(rawLink)
        if (parsed is PairingParseResult.Invalid) return PairingResult.Invalid(parsed.error.message)
        val credentials = (parsed as PairingParseResult.Valid).credentials
        if (!transportAllowed(credentials.relayOrigin)) {
            return PairingResult.Invalid("Release builds only connect over wss://")
        }
        val current = store.storedPairings()
        if (current is StoreResult.Error) return PairingResult.StorageError(current.message)
        val list = (current as StoreResult.Ok).value.toMutableList()
        val existingIndex = list.indexOfFirst {
            it.relayOrigin == credentials.relayOrigin && it.roomId == credentials.roomId
        }
        val requestedLabel = label?.trim().orEmpty()
        val id: String
        val resolvedLabel: String
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            id = existing.id
            resolvedLabel = if (requestedLabel.isEmpty()) existing.label else requestedLabel
            list[existingIndex] = PairingStore.storedFrom(id, resolvedLabel, credentials)
        } else {
            id = PairingStore.newPairingId()
            resolvedLabel = if (requestedLabel.isEmpty()) {
                defaultLabel(credentials)
            } else {
                requestedLabel
            }
            list += PairingStore.storedFrom(id, resolvedLabel, credentials)
        }
        return when (val saved = store.replaceAll(list)) {
            is StoreResult.Error -> PairingResult.StorageError(saved.message)
            is StoreResult.Ok -> {
                stored = list
                _pairings.value = list.map(StoredPairing::toSaved)
                selectInternal(id, persist = true)
                PairingResult.Saved(id)
            }
        }
    }

    override suspend fun select(id: String) {
        if (stored.none { it.id == id }) {
            publishNotice("Pairing not found")
            return
        }
        selectInternal(id, persist = true)
    }

    private fun selectInternal(id: String, persist: Boolean) {
        val target = stored.firstOrNull { it.id == id } ?: return
        val credentials = target.toCredentials()
        if (credentials == null) {
            storageError = CREDENTIAL_RECOVERY_MESSAGE
            publishSession()
            return
        }
        if (!transportAllowed(credentials.relayOrigin)) {
            publishNotice("Release builds only connect over wss://")
            return
        }
        selectedId = id
        autoConnectWanted = true
        notice = null
        if (persist) scope.launch { store.setLastPairingId(id) }
        client.connect(credentials, _nickname.value)
        publishSession()
    }

    override suspend fun rename(id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            publishNotice("Label cannot be empty")
            return
        }
        val list = stored.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(label = trimmed)
        when (val result = store.replaceAll(list)) {
            is StoreResult.Error -> publishNotice(result.message)
            is StoreResult.Ok -> {
                stored = list
                _pairings.value = list.map(StoredPairing::toSaved)
                publishSession()
            }
        }
    }

    override suspend fun forget(id: String) {
        val list = stored.filterNot { it.id == id }
        when (val result = store.replaceAll(list)) {
            is StoreResult.Error -> publishNotice(result.message)
            is StoreResult.Ok -> {
                stored = list
                _pairings.value = list.map(StoredPairing::toSaved)
                if (selectedId == id) {
                    selectedId = null
                    autoConnectWanted = false
                    client.clearSession()
                    store.setLastPairingId(null)
                }
                publishSession()
            }
        }
    }

    override fun retry() {
        val target = stored.firstOrNull { it.id == selectedId } ?: return
        val credentials = target.toCredentials()
        if (credentials == null) {
            storageError = CREDENTIAL_RECOVERY_MESSAGE
            publishSession()
            return
        }
        if (!transportAllowed(credentials.relayOrigin)) {
            publishNotice("Release builds only connect over wss://")
            return
        }
        autoConnectWanted = true
        scope.launch { store.setLastPairingId(target.id) }
        when (client.state.value.status) {
            ConnectionStatus.Idle, ConnectionStatus.Paused, ConnectionStatus.WaitingForNetwork ->
                client.connect(credentials, _nickname.value)

            else -> client.retry()
        }
    }

    override fun disconnect() {
        autoConnectWanted = false
        client.disconnect()
        scope.launch { store.setLastPairingId(null) }
    }

    override suspend fun sendPrompt(text: String, images: List<PromptImage>): SendResult =
        client.sendPrompt(text, images)

    override suspend fun interrupt(): SendResult = client.interrupt()

    override suspend fun respond(reqId: Long, value: String?): SendResult = client.respond(reqId, value)

    override suspend fun sendAgentCommand(
        command: AgentCommand,
        agentId: String,
        text: String?,
    ): SendResult = client.sendAgentCommand(command, agentId, text)

    override suspend fun fetchTranscript(agentId: String, fromByte: Long): TranscriptResult =
        client.fetchTranscript(agentId, fromByte)

    override suspend fun setNickname(value: String) {
        val result = store.setNickname(value)
        if (result is StoreResult.Error) {
            publishNotice(result.message)
        } else {
            _nickname.value = value.trim().take(PairingStore.MAX_NICKNAME)
                .ifEmpty { PairingStore.DEFAULT_NICKNAME }
        }
    }

    override suspend fun setTheme(mode: ThemeMode) {
        val result = store.setTheme(mode)
        if (result is StoreResult.Error) {
            publishNotice(result.message)
        } else {
            _theme.value = mode
        }
    }

    private fun defaultLabel(credentials: PairingCredentials): String {
        val host = credentials.relayOrigin.removePrefix("wss://").removePrefix("ws://")
        return "Session $host / ${credentials.roomId.take(6)}"
    }

    private fun transportAllowed(origin: String): Boolean =
        BuildConfig.DEBUG || origin.startsWith("wss://")

    private fun maybeConnect() {
        if (!restoreDone || !foreground || !networkReady || !autoConnectWanted) return
        val target = stored.firstOrNull { it.id == selectedId } ?: return
        val credentials = target.toCredentials() ?: return
        if (!transportAllowed(credentials.relayOrigin)) {
            publishNotice("Release builds only connect over wss://")
            return
        }
        when (client.state.value.status) {
            ConnectionStatus.Idle, ConnectionStatus.Paused, ConnectionStatus.WaitingForNetwork ->
                client.connect(credentials, _nickname.value)

            else -> Unit
        }
    }

    // ── SessionRuntimeHost ────────────────────────────────────────────────

    override fun onForeground(networkReady: Boolean) {
        foreground = true
        this.networkReady = networkReady
        if (networkReady) maybeConnect()
    }

    override fun onBackground() {
        foreground = false
        client.pause()
    }

    override fun onNetworkUnavailable() {
        networkReady = false
        client.networkLost()
    }

    override fun onNetworkAvailable(changed: Boolean) {
        networkReady = true
        if (!foreground || !autoConnectWanted) return
        val current = client.state.value.status
        if (current is ConnectionStatus.Ended || current is ConnectionStatus.Failed) return
        val target = stored.firstOrNull { it.id == selectedId }
        if (current == ConnectionStatus.Idle || current == ConnectionStatus.Paused ||
            current == ConnectionStatus.WaitingForNetwork
        ) {
            target?.toCredentials()?.takeIf { transportAllowed(it.relayOrigin) }
                ?.let { client.connect(it, _nickname.value) }
            return
        }
        if (changed) client.networkChanged() else client.networkValidated()
    }

    override fun onNetworkValidated() {
        client.networkValidated()
    }
}
