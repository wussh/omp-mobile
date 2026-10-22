package dev.omp.mobile.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.decodeBase64Url
import dev.omp.mobile.collab.encodeBase64Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val CREDENTIAL_RECOVERY_MESSAGE =
    "Kredensial tidak dapat dibuka; hapus data aplikasi melalui Pengaturan Android lalu pair ulang"

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** UI-safe pairing metadata. Room key and write token never leave [PairingStore]. */
data class SavedPairing(
    val id: String,
    val label: String,
    val relayOrigin: String,
    val roomId: String,
    val hasWriteToken: Boolean,
) {
    val relayHost: String
        get() = relayOrigin.removePrefix("wss://").removePrefix("ws://")

    val accessLabel: String
        get() = if (hasWriteToken) "Control" else "View"
}

/** Full credential row, kept inside the data layer. */
data class StoredPairing(
    val id: String,
    val label: String,
    val relayOrigin: String,
    val roomId: String,
    val roomKey: String,
    val writeToken: String?,
) {
    fun toSaved(): SavedPairing = SavedPairing(
        id = id,
        label = label,
        relayOrigin = relayOrigin,
        roomId = roomId,
        hasWriteToken = writeToken != null,
    )

    override fun toString(): String =
        "StoredPairing(id=$id, label=$label, relayOrigin=$relayOrigin, roomId=$roomId, roomKey=<redacted>, hasWriteToken=${writeToken != null})"

    fun toCredentials(): PairingCredentials? {
        val key = decodeBase64Url(roomKey) ?: return null
        if (key.size != 32) return null
        val token = writeToken?.let { decodeBase64Url(it) } ?: null
        if (writeToken != null && (token == null || token.size != 16)) return null
        return PairingCredentials(relayOrigin, roomId, key, token)
    }
}

sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>
    data class Error(val message: String = CREDENTIAL_RECOVERY_MESSAGE) : StoreResult<Nothing>
}

@Serializable
private data class VaultPayload(
    val version: Int = 1,
    val pairings: List<StoredPairingDto> = emptyList(),
)

@Serializable
private data class StoredPairingDto(
    val id: String,
    val label: String,
    val relayOrigin: String,
    val roomId: String,
    val roomKey: String,
    val writeToken: String? = null,
)

private val VaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Single DataStore-backed vault in `noBackupFilesDir`, encrypted with an
 * AES-256-GCM key in AndroidKeyStore. The preference file itself never holds
 * plaintext room keys or write tokens.
 */
class PairingStore @VisibleForTesting constructor(
    context: Context,
    private val file: File = File(context.noBackupFilesDir, FILE_NAME),
    private val alias: String = KEY_ALIAS,
    storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
) {
    @Inject
    @VisibleForTesting
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        file = File(context.noBackupFilesDir, FILE_NAME),
        alias = KEY_ALIAS,
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
    )
    private val mutex = Mutex()
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = storeScope,
        produceFile = { file },
    )

    /** Full credential rows, consumed only by the repository. */
    val storedPairingsFlow: Flow<StoreResult<List<StoredPairing>>> = dataStore.data
        .map { prefs -> readVault(prefs) }
        .catch { emit(StoreResult.Error()) }
        .flowOn(Dispatchers.IO)

    /** UI-visible pairings; storage failures are surfaced instead of an empty vault. */
    val pairings: Flow<StoreResult<List<SavedPairing>>> = storedPairingsFlow
        .map { result ->
            when (result) {
                is StoreResult.Ok -> StoreResult.Ok(result.value.map(StoredPairing::toSaved))
                is StoreResult.Error -> result
            }
        }
        .flowOn(Dispatchers.IO)

    val nickname: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[NICKNAME]?.takeIf(String::isNotBlank) ?: DEFAULT_NICKNAME }
        .flowOn(Dispatchers.IO)

    val theme: Flow<ThemeMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            when (prefs[THEME]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }
        .flowOn(Dispatchers.IO)

    val lastPairingId: Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[LAST_PAIRING_ID] }
        .flowOn(Dispatchers.IO)

    val filePath: String get() = file.absolutePath

    suspend fun storedPairings(): StoreResult<List<StoredPairing>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = try {
                dataStore.data.first()
            } catch (e: Exception) {
                return@withLock StoreResult.Error()
            }
            readVault(prefs)
        }
    }

    suspend fun credentialsFor(id: String): StoreResult<PairingCredentials> = withContext(Dispatchers.IO) {
        when (val result = storedPairings()) {
            is StoreResult.Error -> result
            is StoreResult.Ok -> {
                val pairing = result.value.firstOrNull { it.id == id }
                    ?: return@withContext StoreResult.Error("Pairing tidak ditemukan")
                pairing.toCredentials()?.let { StoreResult.Ok(it) } ?: StoreResult.Error()
            }
        }
    }

    suspend fun replaceAll(items: List<StoredPairing>): StoreResult<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeVault(items.map(::toDto))
        }
    }

    suspend fun setNickname(value: String): StoreResult<Unit> = withContext(Dispatchers.IO) {
        val sanitized = value.trim().take(MAX_NICKNAME)
        try {
            dataStore.edit { prefs ->
                if (sanitized.isEmpty()) prefs.remove(NICKNAME) else prefs[NICKNAME] = sanitized
            }
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            StoreResult.Error()
        }
    }

    suspend fun setTheme(mode: ThemeMode): StoreResult<Unit> = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { it[THEME] = mode.name }
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            StoreResult.Error()
        }
    }

    suspend fun setLastPairingId(id: String?): StoreResult<Unit> = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { prefs ->
                if (id == null) prefs.remove(LAST_PAIRING_ID) else prefs[LAST_PAIRING_ID] = id
            }
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            StoreResult.Error()
        }
    }

    private suspend fun writeVault(items: List<StoredPairingDto>): StoreResult<Unit> {
        val existing = try {
            dataStore.data.first()
        } catch (e: Exception) {
            return StoreResult.Error()
        }
        val vaultPresent = existing[VAULT] != null
        if (vaultPresent) {
            when (readVault(existing)) {
                is StoreResult.Error -> return StoreResult.Error()
                is StoreResult.Ok -> Unit
            }
        }
        val key = existingKey()
        if (key == null && vaultPresent) return StoreResult.Error()
        val activeKey = key ?: createKey() ?: return StoreResult.Error()
        val payload = VaultJson.encodeToString(VaultPayload.serializer(), VaultPayload(pairings = items))
        val encrypted = try {
            encrypt(activeKey, payload)
        } catch (e: Exception) {
            return StoreResult.Error()
        }
        return try {
            dataStore.edit { it[VAULT] = encrypted }
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            StoreResult.Error()
        }
    }

    private fun readVault(prefs: Preferences): StoreResult<List<StoredPairing>> {
        val encoded = prefs[VAULT]
        if (encoded == null) return StoreResult.Ok(emptyList())
        if (encoded.isEmpty()) return StoreResult.Error()
        val key = existingKey() ?: return StoreResult.Error()
        val plaintext = try {
            decrypt(key, encoded)
        } catch (e: Exception) {
            return StoreResult.Error()
        }
        val payload = try {
            VaultJson.decodeFromString(VaultPayload.serializer(), plaintext)
        } catch (e: Exception) {
            return StoreResult.Error()
        }
        if (payload.version != 1) return StoreResult.Error()
        val items = payload.pairings.mapNotNull { dto ->
            val keyBytes = decodeBase64Url(dto.roomKey) ?: return@mapNotNull null
            val token = if (dto.writeToken != null) {
                decodeBase64Url(dto.writeToken) ?: return@mapNotNull null
            } else {
                null
            }
            if (keyBytes.size != 32) return@mapNotNull null
            if (token != null && token.size != 16) return@mapNotNull null
            if (dto.id.isBlank()) return@mapNotNull null
            StoredPairing(dto.id, dto.label, dto.relayOrigin, dto.roomId, dto.roomKey, dto.writeToken)
        }
        if (items.size != payload.pairings.size) return StoreResult.Error()
        return StoreResult.Ok(items)
    }

    private fun existingKey(): SecretKey? = try {
        keyStore.getKey(alias, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun createKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
    } catch (e: Exception) {
        null
    }

    private fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(VAULT_AAD)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size == 12) { "Unexpected GCM IV length" }
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun decrypt(key: SecretKey, encoded: String): String {
        val sealed = java.util.Base64.getDecoder().decode(encoded)
        require(sealed.size > 12 + 16) { "Vault ciphertext too short" }
        val iv = sealed.copyOfRange(0, 12)
        val ciphertext = sealed.copyOfRange(12, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(VAULT_AAD)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun toDto(pairing: StoredPairing): StoredPairingDto = StoredPairingDto(
        id = pairing.id,
        label = pairing.label,
        relayOrigin = pairing.relayOrigin,
        roomId = pairing.roomId,
        roomKey = pairing.roomKey,
        writeToken = pairing.writeToken,
    )

    companion object {
        const val FILE_NAME = "omp.preferences_pb"
        const val KEY_ALIAS = "omp.mobile.pairings.v1"
        const val DEFAULT_NICKNAME = "Android"
        const val MAX_NICKNAME = 64
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val VAULT_AAD = "omp-mobile:pairings:v1".toByteArray(Charsets.UTF_8)

        private val VAULT = stringPreferencesKey("vault_v1")
        private val NICKNAME = stringPreferencesKey("nickname")
        private val THEME = stringPreferencesKey("theme")
        private val LAST_PAIRING_ID = stringPreferencesKey("last_pairing_id")

        fun newPairingId(): String = UUID.randomUUID().toString()

        fun roomKeyText(credentials: PairingCredentials): String = encodeBase64Url(credentials.key)

        fun tokenText(credentials: PairingCredentials): String? =
            credentials.writeToken?.let(::encodeBase64Url)

        fun storedFrom(
            id: String,
            label: String,
            credentials: PairingCredentials,
        ): StoredPairing = StoredPairing(
            id = id,
            label = label,
            relayOrigin = credentials.relayOrigin,
            roomId = credentials.roomId,
            roomKey = roomKeyText(credentials),
            writeToken = tokenText(credentials),
        )
    }
}
