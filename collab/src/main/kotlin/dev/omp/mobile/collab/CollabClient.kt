package dev.omp.mobile.collab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Syncing(val received: Int, val total: Int) : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long, val message: String?) : ConnectionStatus
    data object WaitingForNetwork : ConnectionStatus
    data object Paused : ConnectionStatus
    data class Ended(val reason: String) : ConnectionStatus
    data class Failed(val kind: FailureKind, val message: String) : ConnectionStatus
}

enum class FailureKind {
    ROOM_NOT_FOUND,
    JOIN_REJECTED,
    PROTOCOL_MISMATCH,
    PROTOCOL_ERROR,
    INVALID_KEY,
    SOCKET,
}

data class ClientState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val replica: ReplicaSnapshot = ReplicaSnapshot(),
    val readOnly: Boolean = false,
    val hasWriteToken: Boolean = false,
    val protocolVersion: Int? = null,
)

sealed interface SendResult {
    data object Queued : SendResult
    data object NotConnected : SendResult
    data object ReadOnly : SendResult
    data object Rejected : SendResult
}

/**
 * One correlated `fetch-transcript` round trip.
 *
 * `Rows` carries decoded JSONL from the requested byte offset and the next
 * offset base (`newSize`). `HostError` is a terminal read failure reported by
 * the host; `Timeout`/`Unavailable` are transient (retrying from the same
 * cursor is correct). `Rejected` means the request was invalid locally.
 */
sealed interface TranscriptResult {
    data class Rows(val text: String, val newSize: Long) : TranscriptResult
    data class HostError(val message: String) : TranscriptResult
    data object Timeout : TranscriptResult
    data object Unavailable : TranscriptResult
    data object Rejected : TranscriptResult
}

/**
 * Single-owner guest client.
 *
 * One actor serializes socket callbacks, decryption, replica reduction, and
 * outbound control frames. Every socket attempt carries a monotonically
 * increasing id so callbacks from a superseded socket cannot mutate a newer
 * session. Transport-level reconnect policy lives here, not in the transport.
 */
class CollabClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val transportFactory: TransportFactory = OkHttpCollabTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val replica = SessionReplica(clock)
    private val messages = Channel<Msg>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(ClientState())
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private var status: ConnectionStatus = ConnectionStatus.Idle
    private var active: Active? = null
    private var currentSocket: TransportSession? = null
    private var attemptId = 0L

    /** Volatile so enqueue-side callers can bind commands to the session they saw. */
    @Volatile
    private var activationId = 0L
    private var attemptCounter = 0
    private var welcomed = false
    private var everWelcomed = false
    private var roomRecovery = false
    private var protocolVersion: Int? = null
    private var autoConnectWanted = false
    private var closeDecidedForAttempt = false
    private var intentionalClose = false

    private var timerSeq = 0L
    private var welcomeToken = 0L
    private var progressToken = 0L
    private var retryToken = 0L
    private var retryScheduled = false
    private var publishJob: Job? = null

    private var transcriptSeq = 0L
    private val pendingTranscripts = mutableMapOf<Long, PendingTranscript>()

    private data class Active(
        val credentials: PairingCredentials,
        val nickname: String,
        val cipher: RoomCipher,
        val activation: Long,
    )

    private data class PendingTranscript(
        val reqId: Long,
        val activation: Long,
        val timerToken: Long,
        val ack: CompletableDeferred<TranscriptResult>,
    )

    init {
        scope.launch {
            for (message in messages) handle(message)
        }
    }

    // ── Public control surface ────────────────────────────────────────────

    /** Starts a fresh activation for [credentials]. Keeps the ledger for the same room. */
    fun connect(credentials: PairingCredentials, nickname: String) {
        messages.trySend(Msg.Connect(credentials, nickname))
    }

    fun disconnect() {
        messages.trySend(Msg.Disconnect)
    }

    /** Manual disconnect that also discards the in-memory transcript (forget pairing). */
    fun clearSession() {
        messages.trySend(Msg.ClearSession)
    }

    fun retry() {
        messages.trySend(Msg.Retry)
    }

    fun pause() {
        messages.trySend(Msg.Pause)
    }

    fun resume() {
        messages.trySend(Msg.Resume)
    }

    fun networkLost() {
        messages.trySend(Msg.NetworkLost)
    }

    /** New default network or transport handoff; starts one fresh attempt. */
    fun networkChanged() {
        messages.trySend(Msg.NetworkChanged)
    }

    /** Validation turned true; only accelerates a pending retry/wait. */
    fun networkValidated() {
        messages.trySend(Msg.NetworkValidated)
    }

    suspend fun sendPrompt(text: String, images: List<PromptImage> = emptyList()): SendResult {
        if (text.isBlank() && images.isEmpty()) return SendResult.Rejected
        val ack = CompletableDeferred<SendResult>()
        val activation = activationId
        if (!messages.trySend(Msg.SendPrompt(text, images, activation, ack)).isSuccess) {
            return SendResult.NotConnected
        }
        return ack.await()
    }

    suspend fun interrupt(): SendResult {
        val ack = CompletableDeferred<SendResult>()
        val activation = activationId
        if (!messages.trySend(Msg.SendInterrupt(activation, ack)).isSuccess) return SendResult.NotConnected
        return ack.await()
    }

    suspend fun respond(reqId: Long, value: String?): SendResult {
        val ack = CompletableDeferred<SendResult>()
        val activation = activationId
        if (!messages.trySend(Msg.SendUiResponse(reqId, value, activation, ack)).isSuccess) {
            return SendResult.NotConnected
        }
        return ack.await()
    }

    /**
     * Sends a subagent command. Mutating: requires a live Control session.
     * There is no command ACK on the wire and no outbound replay.
     */
    suspend fun sendAgentCommand(command: AgentCommand, agentId: String, text: String? = null): SendResult {
        val ack = CompletableDeferred<SendResult>()
        val activation = activationId
        if (!messages.trySend(Msg.SendAgentCommand(command, agentId, text, activation, ack)).isSuccess) {
            return SendResult.NotConnected
        }
        return ack.await()
    }

    /**
     * Reads a subagent transcript from [fromByte]. Any joined session may read
     * (View links included); the request resolves after a 10s host timeout.
     */
    suspend fun fetchTranscript(agentId: String, fromByte: Long): TranscriptResult {
        if (agentId.isBlank() || fromByte < 0) return TranscriptResult.Rejected
        val ack = CompletableDeferred<TranscriptResult>()
        val activation = activationId
        if (!messages.trySend(Msg.FetchTranscript(agentId, fromByte, activation, ack)).isSuccess) {
            return TranscriptResult.Unavailable
        }
        return ack.await()
    }

    /** Test/smoke hook: publish pending coalesced stream updates immediately. */
    fun flush() {
        messages.trySend(Msg.Flush)
    }

    // ── Actor ─────────────────────────────────────────────────────────────

    private fun handle(message: Msg) {
        when (message) {
            is Msg.Connect -> startActivation(message.credentials, message.nickname, clearIfDifferent = true)
            Msg.Disconnect -> disconnectNow()
            Msg.ClearSession -> clearSessionNow()
            Msg.Retry -> manualRetry()
            Msg.Pause -> pauseNow()
            Msg.Resume -> resumeNow()
            Msg.NetworkLost -> networkLostNow()
            Msg.NetworkChanged -> networkChangedNow()
            Msg.NetworkValidated -> networkValidatedNow()
            is Msg.Open -> onOpen(message.attempt)
            is Msg.Binary -> onBinary(message.attempt, message.bytes)
            is Msg.Text -> onText(message.attempt, message.text)
            is Msg.Closing -> onClosed(message.attempt, message.code, message.reason)
            is Msg.Closed -> onClosed(message.attempt, message.code, message.reason)
            is Msg.Failure -> onFailure(message.attempt, message.error)
            is Msg.WelcomeTimeout -> onWelcomeTimeout(message.activation, message.attempt, message.token)
            is Msg.ProgressTimeout -> onProgressTimeout(message.activation, message.attempt, message.token)
            is Msg.RetryTick -> onRetryTick(message.activation, message.token)
            is Msg.SendPrompt ->
                message.ack.complete(sendPromptNow(message.text, message.images, message.activation))

            is Msg.SendInterrupt -> message.ack.complete(sendInterruptNow(message.activation))
            is Msg.SendUiResponse ->
                message.ack.complete(sendUiResponseNow(message.reqId, message.value, message.activation))

            is Msg.SendAgentCommand -> message.ack.complete(
                sendAgentCommandNow(message.command, message.agentId, message.text, message.activation),
            )

            is Msg.FetchTranscript ->
                fetchTranscriptNow(message.agentId, message.fromByte, message.activation, message.ack)
            is Msg.TranscriptTimeout ->
                onTranscriptTimeout(message.activation, message.reqId, message.token)

            Msg.Flush -> publishNow()
        }
    }

    private fun startActivation(credentials: PairingCredentials, nickname: String, clearIfDifferent: Boolean) {
        val cipher = try {
            RoomCipher.fromKey(credentials.key)
        } catch (e: IllegalArgumentException) {
            fail(FailureKind.INVALID_KEY, "Invalid session key")
            return
        }
        val previous = active?.credentials
        if (clearIfDifferent && previous != null &&
            (previous.relayOrigin != credentials.relayOrigin || previous.roomId != credentials.roomId)
        ) {
            replica.clear()
        }
        invalidateSocket()
        cancelTimers()
        activationId++
        attemptCounter = 0
        welcomed = false
        everWelcomed = false
        roomRecovery = false
        protocolVersion = null
        autoConnectWanted = true
        active = Active(credentials, nickname, cipher, activationId)
        replica.onDisconnected()
        status = ConnectionStatus.Connecting
        openSocket()
        publishNow()
    }

    private fun manualRetry() {
        val current = active ?: return
        startActivation(current.credentials, current.nickname, clearIfDifferent = false)
    }

    private fun resumeNow() {
        if (!autoConnectWanted) return
        val current = active ?: return
        when (status) {
            ConnectionStatus.Paused, ConnectionStatus.WaitingForNetwork ->
                startActivation(current.credentials, current.nickname, clearIfDifferent = false)

            else -> Unit
        }
    }

    private fun disconnectNow() {
        autoConnectWanted = false
        invalidateSocket()
        cancelTimers()
        active = null
        welcomed = false
        replica.onDisconnected()
        status = ConnectionStatus.Idle
        publishNow()
    }

    private fun clearSessionNow() {
        autoConnectWanted = false
        invalidateSocket()
        cancelTimers()
        active = null
        welcomed = false
        replica.clear()
        status = ConnectionStatus.Idle
        publishNow()
    }

    private fun pauseNow() {
        if (!autoConnectWanted) return
        when (status) {
            is ConnectionStatus.Ended, is ConnectionStatus.Failed, ConnectionStatus.Idle -> return
            ConnectionStatus.Paused -> return
            else -> Unit
        }
        invalidateSocket()
        cancelTimers()
        replica.onDisconnected()
        status = ConnectionStatus.Paused
        publishNow()
    }

    private fun networkLostNow() {
        if (!autoConnectWanted || active == null) return
        when (status) {
            is ConnectionStatus.Ended, is ConnectionStatus.Failed, ConnectionStatus.Idle,
            ConnectionStatus.Paused, ConnectionStatus.WaitingForNetwork -> return

            else -> Unit
        }
        invalidateSocket()
        cancelTimers()
        replica.onDisconnected()
        status = ConnectionStatus.WaitingForNetwork
        publishNow()
    }

    private fun networkChangedNow() {
        if (!autoConnectWanted) return
        val current = active ?: return
        when (status) {
            is ConnectionStatus.Ended, is ConnectionStatus.Failed, ConnectionStatus.Idle,
            ConnectionStatus.Paused -> return

            else -> startActivation(current.credentials, current.nickname, clearIfDifferent = false)
        }
    }

    private fun networkValidatedNow() {
        if (!autoConnectWanted) return
        val current = active ?: return
        when (status) {
            ConnectionStatus.WaitingForNetwork -> startActivation(current.credentials, current.nickname, false)
            is ConnectionStatus.Reconnecting -> if (retryScheduled) {
                cancelRetryTimer()
                openSocket()
                publishNow()
            }

            else -> Unit
        }
    }

    private fun openSocket() {
        val current = active ?: return
        attemptId++
        val id = attemptId
        closeDecidedForAttempt = false
        intentionalClose = false
        welcomed = false
        welcomeToken = ++timerSeq
        progressToken = ++timerSeq
        cancelRetryTimer()
        if (status !is ConnectionStatus.Connected) {
            status = if (attemptCounter == 0) ConnectionStatus.Connecting
            else ConnectionStatus.Reconnecting(attemptCounter, 0, "Connecting")
        }
        val listener = object : TransportListener {
            override fun onOpen() {
                messages.trySend(Msg.Open(id))
            }

            override fun onBinary(bytes: ByteArray) {
                messages.trySend(Msg.Binary(id, bytes))
            }

            override fun onText(text: String) {
                messages.trySend(Msg.Text(id, text))
            }

            override fun onClosing(code: Int, reason: String) {
                messages.trySend(Msg.Closing(id, code, reason))
            }

            override fun onClosed(code: Int, reason: String) {
                messages.trySend(Msg.Closed(id, code, reason))
            }

            override fun onFailure(error: Throwable) {
                messages.trySend(Msg.Failure(id, error))
            }
        }
        currentSocket = try {
            transportFactory.create(current.credentials.wsUrl, listener)
        } catch (e: Exception) {
            scheduleRetry("Failed to open connection")
            return
        }
    }

    private fun onOpen(id: Long) {
        if (id != attemptId || !isLiveAttempt()) return
        val current = active ?: return
        welcomed = false
        val token = current.credentials.writeToken?.let(::encodeBase64Url)
        val hello = encodeGuestFrame(GuestFrame.Hello(current.nickname, token))
        val sent = runCatching { sendEncrypted(current, hello) }.getOrDefault(false)
        if (!sent) {
            invalidateSocket()
            scheduleRetry("Failed to send hello")
            return
        }
        val activation = activationId
        welcomeToken = ++timerSeq
        val tokenId = welcomeToken
        val attempt = id
        scope.launch {
            delay(WELCOME_TIMEOUT_MS)
            messages.trySend(Msg.WelcomeTimeout(activation, attempt, tokenId))
        }
        publishNow()
    }

    private fun onBinary(id: Long, bytes: ByteArray) {
        if (id != attemptId || !isLiveAttempt()) return
        val current = active ?: return
        val envelope = unpackEnvelope(bytes) ?: return
        val text = try {
            current.cipher.open(envelope.payload)
        } catch (e: Exception) {
            fail(FailureKind.PROTOCOL_ERROR, "Session frame could not be opened")
            return
        }
        val frame = try {
            parseHostFrame(text)
        } catch (e: WireFormatException) {
            fail(FailureKind.PROTOCOL_ERROR, "Invalid session frame")
            return
        }
        handleFrame(frame)
    }

    private fun onText(id: Long, text: String) {
        if (id != attemptId || !isLiveAttempt()) return
        // Relay control only. Plaintext session JSON is never processed.
        if (text.contains("\"room-closed\"")) {
            // Hint only: tear down and let the close path decide whether the
            // session may recover. It does not prove the session ended.
            invalidateSocket()
            cancelPendingTranscripts()
            scheduleRetry("Room closed temporarily")
        }
    }

    private fun handleFrame(frame: HostFrame) {
        when (frame) {
            is HostFrame.Welcome -> onWelcome(frame)
            is HostFrame.SnapshotChunk -> if (welcomed) {
                replica.onSnapshotChunk(frame)
                val loading = replica.snapshot().loading
                if (loading == null) {
                    cancelProgressTimer()
                    status = ConnectionStatus.Connected
                    attemptCounter = 0
                } else {
                    status = ConnectionStatus.Syncing(loading.received, loading.total)
                    armProgressTimeout()
                }
                publishNow()
            }

            is HostFrame.EntryFrame -> if (welcomed) {
                replica.onEntry(frame.entry)
                publish(immediate = true)
            }

            is HostFrame.EventFrame -> if (welcomed) {
                replica.onEvent(frame.event)
                publish(immediate = !isCoalescable(frame.event))
            }

            is HostFrame.StateFrame -> if (welcomed) {
                replica.onState(frame.state)
                publish(immediate = true)
            }

            is HostFrame.AgentsFrame -> if (welcomed) {
                replica.onAgents(frame.agents)
                publish(immediate = true)
            }

            is HostFrame.BusFrame -> if (welcomed) {
                replica.onBus(frame.channel, frame.data)
                // Progress mirrors can be chatty; lifecycle transitions publish now.
                publish(immediate = frame.channel != "task:subagent:progress")
            }

            is HostFrame.Transcript -> resolveTranscript(frame)
            is HostFrame.UiRequestFrame -> if (welcomed) {
                replica.onUiRequest(frame.request)
                publish(immediate = true)
            }

            is HostFrame.UiRequestEnd -> if (welcomed) {
                replica.onUiRequestEnd(frame.reqId)
                publish(immediate = true)
            }

            is HostFrame.Bye -> endNow(frame.reason)
            is HostFrame.Error -> {
                if (!welcomed) fail(FailureKind.JOIN_REJECTED, frame.message)
                else {
                    replica.pushNotice("error", frame.message)
                    publish(immediate = true)
                }
            }

            is HostFrame.Unknown -> Unit
        }
    }

    private fun onWelcome(frame: HostFrame.Welcome) {
        if (welcomed) return
        if (frame.proto != COLLAB_PROTO) {
            fail(
                FailureKind.PROTOCOL_MISMATCH,
                "Host uses protocol ${frame.proto}; this app requires version 3",
            )
            return
        }
        cancelWelcomeTimer()
        welcomed = true
        everWelcomed = true
        protocolVersion = frame.proto
        replica.onWelcome(frame)
        if (frame.entryCount <= 0) {
            status = ConnectionStatus.Connected
            attemptCounter = 0
            cancelProgressTimer()
        } else {
            status = ConnectionStatus.Syncing(0, frame.entryCount)
            armProgressTimeout()
        }
        publishNow()
    }

    private fun armProgressTimeout() {
        val activation = activationId
        val attempt = attemptId
        progressToken = ++timerSeq
        val token = progressToken
        scope.launch {
            delay(SNAPSHOT_PROGRESS_TIMEOUT_MS)
            messages.trySend(Msg.ProgressTimeout(activation, attempt, token))
        }
    }

    private fun onWelcomeTimeout(activation: Long, attempt: Long, token: Long) {
        if (activation != activationId || attempt != attemptId || token != welcomeToken) return
        if (welcomed) return
        invalidateSocket()
        scheduleRetry("Host did not respond")
    }

    private fun onProgressTimeout(activation: Long, attempt: Long, token: Long) {
        if (activation != activationId || attempt != attemptId || token != progressToken) return
        if (replica.snapshot().loading == null) return
        invalidateSocket()
        scheduleRetry("Host did not send the snapshot")
    }

    private fun onClosed(id: Long, code: Int, reason: String) {
        if (id != attemptId) return
        if (closeDecidedForAttempt) return
        if (intentionalClose) return
        closeDecidedForAttempt = true
        currentSocket = null
        cancelTimers()

        if (status is ConnectionStatus.Ended || status is ConnectionStatus.Failed) return
        if (!autoConnectWanted || status is ConnectionStatus.Paused) return

        replica.onDisconnected()
        when (code) {
            4001 -> {
                roomRecovery = true
                scheduleRetry("Room is being recreated")
            }

            4004 -> if (everWelcomed || roomRecovery) {
                scheduleRetry("Room is not available yet")
            } else {
                fail(
                    FailureKind.ROOM_NOT_FOUND,
                    "Room not found; pair again or retry after the host is ready",
                )
            }

            4009, 4029 -> fail(
                FailureKind.JOIN_REJECTED,
                if (code == 4029) "Room is full" else "Another host is already connected to this room",
            )

            else -> scheduleRetry(reason.ifBlank { "Connection lost" })
        }
    }

    private fun onFailure(id: Long, error: Throwable) {
        if (id != attemptId) return
        onClosed(id, 1006, "Connection lost")
    }

    private fun scheduleRetry(message: String) {
        if (!autoConnectWanted) return
        if (status is ConnectionStatus.Ended || status is ConnectionStatus.Failed) return
        cancelRetryTimer()
        val base = minOf(BACKOFF_BASE_MS shl attemptCounter.coerceAtMost(5), BACKOFF_MAX_MS)
        attemptCounter++
        val factor = 0.75 + random.nextDouble() * 0.5
        val delayMs = (base * factor).toLong().coerceAtLeast(0)
        status = ConnectionStatus.Reconnecting(attemptCounter, delayMs, message)
        retryScheduled = true
        retryToken = ++timerSeq
        val token = retryToken
        val activation = activationId
        scope.launch {
            delay(delayMs)
            messages.trySend(Msg.RetryTick(activation, token))
        }
        publishNow()
    }

    private fun onRetryTick(activation: Long, token: Long) {
        if (activation != activationId || token != retryToken) return
        if (!retryScheduled || !autoConnectWanted) return
        retryScheduled = false
        if (status is ConnectionStatus.Ended || status is ConnectionStatus.Failed) return
        openSocket()
        publishNow()
    }

    private fun endNow(reason: String) {
        autoConnectWanted = false
        invalidateSocket()
        cancelTimers()
        replica.onDisconnected()
        status = ConnectionStatus.Ended(reason)
        publishNow()
    }

    private fun fail(kind: FailureKind, message: String) {
        autoConnectWanted = false
        invalidateSocket()
        cancelTimers()
        replica.onDisconnected()
        status = ConnectionStatus.Failed(kind, message)
        publishNow()
    }

    // ── Outbound ──────────────────────────────────────────────────────────

    /** True when the command was enqueued for a session that is still current. */
    private fun isCurrentActivation(expected: Long): Boolean = expected == activationId

    private fun sendPromptNow(text: String, images: List<PromptImage>, expected: Long): SendResult {
        if (text.isBlank() && images.isEmpty()) return SendResult.Rejected
        if (!isCurrentActivation(expected)) return SendResult.Rejected
        if (status !is ConnectionStatus.Connected) return SendResult.NotConnected
        return sendMutating(GuestFrame.Prompt(text, images))
    }

    private fun sendInterruptNow(expected: Long): SendResult {
        if (!isCurrentActivation(expected)) return SendResult.Rejected
        if (status !is ConnectionStatus.Connected) return SendResult.NotConnected
        return sendMutating(GuestFrame.Abort)
    }

    private fun sendUiResponseNow(reqId: Long, value: String?, expected: Long): SendResult {
        if (!isCurrentActivation(expected)) return SendResult.Rejected
        if (status !is ConnectionStatus.Connected) return SendResult.NotConnected
        if (replica.snapshot().readOnly || active?.credentials?.hasWriteToken != true) return SendResult.ReadOnly
        if (!replica.markUiAnswered(reqId)) return SendResult.Rejected
        val result = sendMutating(GuestFrame.UiResponse(reqId, value))
        if (result != SendResult.Queued) replica.unmarkUiAnswered(reqId)
        return result
    }

    private fun sendAgentCommandNow(
        command: AgentCommand,
        agentId: String,
        text: String?,
        expected: Long,
    ): SendResult {
        if (!isCurrentActivation(expected)) return SendResult.Rejected
        if (status !is ConnectionStatus.Connected) return SendResult.NotConnected
        if (agentId.isBlank()) return SendResult.Rejected
        val wireText = when (command) {
            AgentCommand.CHAT -> text?.takeIf { it.isNotBlank() } ?: return SendResult.Rejected
            AgentCommand.KILL, AgentCommand.REVIVE -> null
        }
        return sendMutating(GuestFrame.AgentCmd(command, agentId, wireText))
    }

    private fun fetchTranscriptNow(
        agentId: String,
        fromByte: Long,
        expected: Long,
        ack: CompletableDeferred<TranscriptResult>,
    ) {
        if (!isCurrentActivation(expected)) {
            ack.complete(TranscriptResult.Unavailable)
            return
        }
        if (status !is ConnectionStatus.Connected) {
            ack.complete(TranscriptResult.Unavailable)
            return
        }
        if (agentId.isBlank() || fromByte < 0) {
            ack.complete(TranscriptResult.Rejected)
            return
        }
        val current = active
        if (current == null) {
            ack.complete(TranscriptResult.Unavailable)
            return
        }
        val reqId = ++transcriptSeq
        val activation = activationId
        val timerToken = ++timerSeq
        pendingTranscripts[reqId] = PendingTranscript(reqId, activation, timerToken, ack)
        val frame = GuestFrame.FetchTranscript(reqId, agentId, fromByte)
        val sent = runCatching { sendEncrypted(current, encodeGuestFrame(frame)) }.getOrDefault(false)
        if (!sent) {
            pendingTranscripts.remove(reqId)
            ack.complete(TranscriptResult.Unavailable)
            return
        }
        scope.launch {
            delay(TRANSCRIPT_TIMEOUT_MS)
            messages.trySend(Msg.TranscriptTimeout(activation, reqId, timerToken))
        }
    }

    private fun resolveTranscript(frame: HostFrame.Transcript) {
        val pending = pendingTranscripts.remove(frame.reqId) ?: return
        pending.ack.complete(
            if (frame.error != null) {
                TranscriptResult.HostError(frame.error)
            } else {
                TranscriptResult.Rows(frame.text, frame.newSize)
            },
        )
    }

    private fun onTranscriptTimeout(activation: Long, reqId: Long, token: Long) {
        val pending = pendingTranscripts[reqId] ?: return
        if (pending.activation != activation || pending.timerToken != token) return
        pendingTranscripts.remove(reqId)
        pending.ack.complete(TranscriptResult.Timeout)
    }

    private fun cancelPendingTranscripts() {
        if (pendingTranscripts.isEmpty()) return
        val pending = pendingTranscripts.values.toList()
        pendingTranscripts.clear()
        for (entry in pending) entry.ack.complete(TranscriptResult.Unavailable)
    }

    private fun sendMutating(frame: GuestFrame): SendResult {
        if (replica.snapshot().readOnly || active?.credentials?.hasWriteToken != true) return SendResult.ReadOnly
        val current = active ?: return SendResult.NotConnected
        return runCatching { sendEncrypted(current, encodeGuestFrame(frame)) }
            .fold(
                onSuccess = { if (it) SendResult.Queued else SendResult.Rejected },
                onFailure = { SendResult.Rejected },
            )
    }

    private fun sendEncrypted(current: Active, json: String): Boolean {
        val socket = currentSocket ?: return false
        val sealed = current.cipher.seal(json)
        val sent = try {
            socket.send(packEnvelope(0, sealed))
        } catch (_: Exception) {
            false
        }
        if (!sent && status is ConnectionStatus.Connected) {
            // Enqueue can fail before a close callback. Recover the socket, never replay the action.
            invalidateSocket()
            cancelTimers()
            replica.onDisconnected()
            scheduleRetry("Connection lost while sending; reconnecting")
        }
        return sent
    }

    // ── Publishing / lifecycle helpers ────────────────────────────────────

    private fun isLiveAttempt(): Boolean =
        status !is ConnectionStatus.Paused &&
            status !is ConnectionStatus.Ended &&
            status !is ConnectionStatus.Failed &&
            status != ConnectionStatus.Idle &&
            status != ConnectionStatus.WaitingForNetwork

    private fun isCoalescable(event: AgentEvent): Boolean =
        event is AgentEvent.MessageUpdate || event is AgentEvent.ToolUpdate

    private fun publish(immediate: Boolean) {
        if (immediate) {
            publishJob?.cancel()
            publishJob = null
            publishNow()
            return
        }
        if (publishJob == null) {
            publishJob = scope.launch {
                delay(PUBLISH_COALESCE_MS)
                publishJob = null
                publishNow()
            }
        }
    }

    private fun publishNow() {
        val snapshot = replica.snapshot()
        _state.value = ClientState(
            status = status,
            replica = snapshot,
            readOnly = snapshot.readOnly,
            hasWriteToken = active?.credentials?.hasWriteToken == true,
            protocolVersion = protocolVersion,
        )
    }

    private fun invalidateSocket() {
        attemptId++
        closeDecidedForAttempt = true
        intentionalClose = true
        val socket = currentSocket
        currentSocket = null
        runCatching { socket?.close(1000, "") }
    }

    private fun cancelTimers() {
        // Epoch change: no pending transcript may resolve a newer socket's request.
        cancelPendingTranscripts()
        welcomeToken = ++timerSeq
        progressToken = ++timerSeq
        cancelRetryTimer()
        publishJob?.cancel()
        publishJob = null
    }

    private fun cancelWelcomeTimer() {
        welcomeToken = ++timerSeq
    }

    private fun cancelProgressTimer() {
        progressToken = ++timerSeq
    }

    private fun cancelRetryTimer() {
        retryScheduled = false
        retryToken = ++timerSeq
    }

    // ── Messages ──────────────────────────────────────────────────────────

    private sealed interface Msg {
        data class Connect(val credentials: PairingCredentials, val nickname: String) : Msg
        data object Disconnect : Msg
        data object ClearSession : Msg
        data object Retry : Msg
        data object Pause : Msg
        data object Resume : Msg
        data object NetworkLost : Msg
        data object NetworkChanged : Msg
        data object NetworkValidated : Msg
        data class Open(val attempt: Long) : Msg
        data class Binary(val attempt: Long, val bytes: ByteArray) : Msg
        data class Text(val attempt: Long, val text: String) : Msg
        data class Closing(val attempt: Long, val code: Int, val reason: String) : Msg
        data class Closed(val attempt: Long, val code: Int, val reason: String) : Msg
        data class Failure(val attempt: Long, val error: Throwable) : Msg
        data class WelcomeTimeout(val activation: Long, val attempt: Long, val token: Long) : Msg
        data class ProgressTimeout(val activation: Long, val attempt: Long, val token: Long) : Msg
        data class RetryTick(val activation: Long, val token: Long) : Msg
        data class SendPrompt(
            val text: String,
            val images: List<PromptImage>,
            val activation: Long,
            val ack: CompletableDeferred<SendResult>,
        ) : Msg
        data class SendInterrupt(
            val activation: Long,
            val ack: CompletableDeferred<SendResult>,
        ) : Msg
        data class SendUiResponse(
            val reqId: Long,
            val value: String?,
            val activation: Long,
            val ack: CompletableDeferred<SendResult>,
        ) : Msg

        data class SendAgentCommand(
            val command: AgentCommand,
            val agentId: String,
            val text: String?,
            val activation: Long,
            val ack: CompletableDeferred<SendResult>,
        ) : Msg

        data class FetchTranscript(
            val agentId: String,
            val fromByte: Long,
            val activation: Long,
            val ack: CompletableDeferred<TranscriptResult>,
        ) : Msg

        data class TranscriptTimeout(val activation: Long, val reqId: Long, val token: Long) : Msg

        data object Flush : Msg
    }

    private companion object {
        const val WELCOME_TIMEOUT_MS = 30_000L
        const val SNAPSHOT_PROGRESS_TIMEOUT_MS = 30_000L
        const val BACKOFF_BASE_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val PUBLISH_COALESCE_MS = 16L
        const val TRANSCRIPT_TIMEOUT_MS = 10_000L
    }
}
