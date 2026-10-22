package dev.omp.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.encodeBase64Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PairingStoreInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val alias = "omp.test.$id"
    private val dir = File(context.noBackupFilesDir, "pairing-test-$id")
    private val file = File(dir, "vault.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val token = ByteArray(16) { (0xa0 + it).toByte() }
    private val credentials = PairingCredentials(
        relayOrigin = "wss://relay.example.com:8443",
        roomId = "AbCdEf123456_-Xy",
        key = key,
        writeToken = token,
    )

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun newStore(scope: CoroutineScope = newScope()): PairingStore =
        PairingStore(context, file, alias, scope)

    private suspend fun cancelAndJoin(scope: CoroutineScope) {
        scope.cancel()
        scope.coroutineContext[Job]?.join()
    }

    @After
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test
    fun roundTripsCredentialsThroughKeystoreAndNeverWritesPlaintext() = runBlocking {
        dir.mkdirs()
        val scope = newScope()
        val store = newStore(scope)
        val stored = PairingStore.storedFrom("pairing-1", "Work", credentials)

        assertTrue(store.replaceAll(listOf(stored)) is StoreResult.Ok)
        val loaded = store.storedPairings()
        assertTrue(loaded is StoreResult.Ok)
        val row = (loaded as StoreResult.Ok).value.single()
        assertEquals("Work", row.label)
        assertTrue(row.toCredentials()!!.key.contentEquals(key))
        assertTrue(row.toCredentials()!!.writeToken!!.contentEquals(token))

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(encodeBase64Url(key)))
        assertFalse(raw.contains(encodeBase64Url(token)))
        assertFalse(raw.contains("AbCdEf123456_-Xy"))
        assertFalse(raw.contains("relay.example.com"))
        assertEquals(PairingStore.DEFAULT_NICKNAME, store.nickname.first())

        // Reopen the same file/alias with a fresh DataStore instance.
        cancelAndJoin(scope)
        val reopened = newStore()
        val again = reopened.storedPairings()
        assertTrue(again is StoreResult.Ok)
        assertTrue((again as StoreResult.Ok).value.single().toCredentials()!!.key.contentEquals(key))
    }

    @Test
    fun emptyFileMeansNoPairings() = runBlocking {
        dir.mkdirs()
        val result = newStore().storedPairings()
        assertTrue(result is StoreResult.Ok)
        assertTrue((result as StoreResult.Ok).value.isEmpty())
    }

    @Test
    fun tamperedCiphertextIsStorageErrorNotEmptyVault() = runBlocking {
        dir.mkdirs()
        val scope = newScope()
        val store = newStore(scope)
        store.replaceAll(listOf(PairingStore.storedFrom("pairing-1", "Work", credentials)))
        cancelAndJoin(scope)

        val editScope = newScope()
        val dataStore = PreferenceDataStoreFactory.create(scope = editScope, produceFile = { file })
        val vaultKey = stringPreferencesKey("vault_v1")
        val encoded = dataStore.data.first()[vaultKey]!!
        val replacement = if (encoded.last() == 'A') 'B' else 'A'
        dataStore.edit { it[vaultKey] = encoded.dropLast(1) + replacement }
        cancelAndJoin(editScope)

        val result = newStore().storedPairings()
        assertTrue(result is StoreResult.Error)
    }

    @Test
    fun missingKeyDoesNotSilentlyBecomeAnEmptyVault() = runBlocking {
        dir.mkdirs()
        val scope = newScope()
        val store = newStore(scope)
        assertTrue(store.replaceAll(listOf(PairingStore.storedFrom("pairing-1", "Work", credentials))) is StoreResult.Ok)
        cancelAndJoin(scope)

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        val result = newStore().storedPairings()
        assertTrue(result is StoreResult.Error)
        // Reading again must not create a fresh key and wipe the ciphertext.
        assertTrue(newStore().storedPairings() is StoreResult.Error)
        assertTrue(file.length() > 0)
    }

    @Test
    fun replacingControlWithViewDropsTheWriteToken() = runBlocking {
        dir.mkdirs()
        val store = newStore()
        val control = PairingStore.storedFrom("pairing-1", "Work", credentials)
        store.replaceAll(listOf(control))

        val view = control.copy(writeToken = null)
        assertTrue(store.replaceAll(listOf(view)) is StoreResult.Ok)
        val row = (store.storedPairings() as StoreResult.Ok).value.single()
        assertFalse(row.toSaved().hasWriteToken)
        assertFalse(row.toCredentials()!!.hasWriteToken)
    }
}
