package com.sankranthi.ledger.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Exercises the real Android Keystore. These cannot be JVM unit tests — the whole
 * point is that the key lives in the platform keystore, so a fake would test
 * nothing worth testing.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Each test gets its own file and its own keystore alias. The
     * `preferencesDataStore` delegate used in production is process-wide, so
     * sharing it here would let the corruption test break every test that ran
     * after it — which is exactly what happened before this was isolated.
     */
    private lateinit var file: File
    private lateinit var alias: String
    private lateinit var scope: CoroutineScope
    private lateinit var store: CredentialStore

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )

    private val copies = mutableListOf<File>()

    /**
     * A cold start reading bytes an earlier instance persisted.
     *
     * Reads a *copy* rather than opening a second store over the same path.
     * DataStore forbids more than one live instance per file, and violating that
     * made three of these tests pass for the wrong reason: the instance error was
     * swallowed into a null, so "returns null" proved nothing about decryption.
     *
     * @param mutate optional corruption of the persisted bytes before reading.
     */
    private fun reopened(mutate: (File) -> Unit = {}): CredentialStore {
        val copy = File(context.cacheDir, "cred-copy-${System.nanoTime()}.preferences_pb")
        file.copyTo(copy, overwrite = true)
        mutate(copy)
        copies += copy
        return CredentialStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { copy },
            ),
            alias,
        )
    }

    private fun session(
        token: String = "session-token-abc123",
        expiresAt: String = Instant.now().plusSeconds(3600).toString(),
        email: String = "ravi@example.com",
    ) = StoredSession(token = token, expiresAt = expiresAt, email = email)

    private fun storeFile(): File = file

    @Before
    fun setUp() {
        val unique = System.nanoTime().toString()
        file = File(context.cacheDir, "cred-test-$unique.preferences_pb")
        file.delete()
        alias = "sankranthi.test.session.$unique"
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = CredentialStore(newDataStore(), alias)
    }

    @After
    fun tearDown() = runTest {
        KeystoreCrypto.deleteKey(alias)
        file.delete()
        copies.forEach { it.delete() }
    }

    @Test
    fun aSavedSessionIsReadBackIntact() = runTest {
        val original = session()

        store.save(original)

        assertEquals(original, store.load())
        assertTrue(store.hasValidSession())
    }

    @Test
    fun nothingIsStoredBeforeAnythingIsSaved() = runTest {
        assertNull(store.load())
        assertFalse(store.hasValidSession())
    }

    @Test
    fun theTokenIsNotRecoverableFromTheFileOnDisk() = runTest {
        store.save(session(token = "SUPER-SECRET-TOKEN", email = "ravi@example.com"))

        val raw = storeFile().readBytes().decodeToString()

        // The point of the whole exercise: pulling the file off the device must
        // not hand over the credential.
        assertFalse("token must not appear in cleartext", raw.contains("SUPER-SECRET-TOKEN"))
        assertFalse("email must not leak either", raw.contains("ravi@example.com"))
    }

    @Test
    fun savingTwiceProducesDifferentCiphertextForTheSamePlaintext() = runTest {
        val fixed = session(token = "same-token", expiresAt = "2030-01-01T00:00:00Z")

        store.save(fixed)
        val first = storeFile().readBytes().copyOf()
        store.save(fixed)
        val second = storeFile().readBytes()

        // AES-GCM with a fresh IV per write. Identical ciphertext would mean a
        // reused IV, which is a serious weakness for GCM.
        assertNotEquals(
            "ciphertext must differ across writes",
            first.toList(),
            second.toList(),
        )
        assertEquals(fixed, store.load())
    }

    @Test
    fun aNewerSaveReplacesTheOlderOne() = runTest {
        store.save(session(token = "first"))
        store.save(session(token = "second"))

        assertEquals("second", store.load()?.token)
    }

    @Test
    fun anExpiredSessionIsTreatedAsAbsentAndCleared() = runTest {
        store.save(session(expiresAt = Instant.now().minusSeconds(60).toString()))

        assertNull(store.load())
        // Cleared as a side effect, so a doomed token is not re-read every sync.
        assertFalse(store.hasValidSession())
        assertNull("second read must also be empty", store.load())
    }

    @Test
    fun anUnparseableExpiryIsTreatedAsExpiredRatherThanValid() = runTest {
        store.save(session(expiresAt = "not-a-date"))

        assertNull("must fail closed, not open", store.load())
    }

    @Test
    fun clearForgetsTheSessionButKeepsTheKey() = runTest {
        store.save(session())
        store.clear()

        assertNull(store.load())
        // The key is per-install, so the next sign-in should not need a keygen.
        assertTrue(KeystoreCrypto.hasKey(alias))
    }

    @Test
    fun aTamperedBlobIsRejectedRatherThanReturningGarbage() = runTest {
        store.save(session(token = "genuine-token"))

        // Flip a byte late in the persisted bytes — inside the ciphertext rather
        // than the DataStore framing — so GCM's tag check is what catches it.
        val loaded = reopened { copy ->
            val bytes = copy.readBytes()
            bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x5A).toByte()
            copy.writeBytes(bytes)
        }.load()

        assertNull("a modified ciphertext must not decrypt", loaded)
    }

    @Test
    fun aCorruptStoreResolvesToNoCredentialInsteadOfCrashing() = runTest {
        store.save(session())

        // A wholly unparseable preferences file, as a partial write or power loss
        // would leave. The corruption handler must reset it instead of throwing
        // on every read for the life of the file.
        val result = runCatching {
            reopened { copy -> copy.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }.load()
        }
        assertTrue(
            "load() must not propagate a failure: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertNull(result.getOrNull())
    }

    @Test
    fun aSessionSurvivesRecreatingTheStore() = runTest {
        store.save(session(token = "persisted-token"))

        assertEquals("persisted-token", reopened().load()?.token)
    }

    @Test
    fun destroyingTheKeyMakesAnExistingBlobUnreadableAndIsHandled() = runTest {
        store.save(session())
        KeystoreCrypto.deleteKey(alias)

        // This is the device-restore case: ciphertext present, key gone.
        assertNull(reopened().load())
    }
}

@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {

    private val alias = "sankranthi.test.key"

    @After
    fun tearDown() {
        KeystoreCrypto.deleteKey(alias)
    }

    @Test
    fun roundTripsArbitraryBytes() {
        val plaintext = "livestock ₹1,23,456.78 — Kurnool".toByteArray()

        val blob = KeystoreCrypto.encrypt(alias, plaintext)
        val recovered = KeystoreCrypto.decrypt(alias, blob)

        assertEquals(plaintext.toList(), recovered.toList())
    }

    @Test
    fun roundTripsEmptyInput() {
        val blob = KeystoreCrypto.encrypt(alias, ByteArray(0))
        assertEquals(0, KeystoreCrypto.decrypt(alias, blob).size)
    }

    @Test
    fun ciphertextIsLongerThanPlaintextByIvAndTag() {
        val plaintext = ByteArray(32)
        val blob = KeystoreCrypto.encrypt(alias, plaintext)

        // 12-byte IV + 16-byte GCM tag.
        assertEquals(plaintext.size + 12 + 16, blob.size)
    }

    @Test
    fun aFlippedBitFailsTheAuthenticationTag() {
        val blob = KeystoreCrypto.encrypt(alias, "sensitive".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()

        assertThrows(KeystoreCrypto.UndecryptableException::class.java) {
            KeystoreCrypto.decrypt(alias, blob)
        }
    }

    @Test
    fun aTruncatedBlobIsRejected() {
        assertThrows(KeystoreCrypto.UndecryptableException::class.java) {
            KeystoreCrypto.decrypt(alias, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun aBlobFromAnotherKeyCannotBeDecrypted() {
        val other = "sankranthi.test.key.other"
        try {
            val blob = KeystoreCrypto.encrypt(other, "secret".toByteArray())

            assertThrows(KeystoreCrypto.UndecryptableException::class.java) {
                KeystoreCrypto.decrypt(alias, blob)
            }
        } finally {
            KeystoreCrypto.deleteKey(other)
        }
    }

    @Test
    fun theKeyIsCreatedOnDemandAndReused() {
        assertFalse(KeystoreCrypto.hasKey(alias))

        val first = KeystoreCrypto.encrypt(alias, "a".toByteArray())
        assertTrue(KeystoreCrypto.hasKey(alias))

        // Reusing the same key means an earlier blob stays readable.
        KeystoreCrypto.encrypt(alias, "b".toByteArray())
        assertEquals("a", KeystoreCrypto.decrypt(alias, first).decodeToString())
    }
}
