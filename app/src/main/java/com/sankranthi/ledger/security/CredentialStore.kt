package com.sankranthi.ledger.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The gateway session token, encrypted at rest.
 *
 * Stored as a single encrypted blob rather than field-by-field so the expiry and
 * the email are protected too — learning "signed in as X until Y" is worth
 * something to an attacker even without the token.
 */
@Serializable
data class StoredSession(
    val token: String,
    /** ISO-8601 instant, as the gateway reported it. */
    val expiresAt: String,
    val email: String,
) {
    /**
     * Whether the token is still worth sending.
     *
     * An unparseable expiry counts as **expired**: better a needless
     * re-authentication than a sync loop against a token the gateway will refuse.
     */
    fun isValid(now: Instant = Instant.now()): Boolean =
        try {
            Instant.parse(expiresAt).isAfter(now)
        } catch (_: DateTimeParseException) {
            false
        }
}

/**
 * A damaged preferences file is reset rather than thrown from.
 *
 * Without this, DataStore raises `CorruptionException` on *every* read for the
 * life of the file, so one partial write or power loss during a save would make
 * the app crash on launch forever. The correct recovery is to forget the
 * credential and sign in again, which costs the user one tap.
 */
private val corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { cause ->
    Log.w("CredentialStore", "Credential store was corrupt; resetting it", cause)
    emptyPreferences()
}

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = CredentialStore.STORE_NAME,
    corruptionHandler = corruptionHandler,
)

/**
 * Persists the long-lived gateway credential.
 *
 * §16 of the requirements says authentication tokens must not be stored in plain
 * text. `androidx.security:security-crypto` — the usual answer — is deprecated,
 * so this is a deliberately small hand-rolled equivalent: AES-GCM via
 * [KeystoreCrypto], ciphertext in DataStore.
 *
 * Every read failure resolves to "no credential", never to a crash. There are
 * several realistic ways the blob becomes unreadable — restored onto a new device
 * where the keystore key does not exist, a key the platform invalidated, storage
 * corruption — and the right response to all of them is to sign in again.
 *
 * The [DataStore] is injected rather than derived from a `Context` so tests can
 * use an isolated file. The `preferencesDataStore` delegate is process-wide, so
 * sharing it across tests would let one test's corruption break the next.
 */
class CredentialStore(
    private val store: DataStore<Preferences>,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Stores [session], replacing anything already held. */
    suspend fun save(session: StoredSession) {
        val blob = KeystoreCrypto.encrypt(keyAlias, json.encodeToString(session).toByteArray())
        val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
        store.edit { it[SESSION_KEY] = encoded }
    }

    /**
     * The stored session, or null if there is none, it cannot be decrypted, or it
     * has expired. An unusable blob is cleared as a side effect so the failure is
     * not re-attempted on every sync.
     */
    suspend fun load(): StoredSession? {
        val encoded = try {
            store.data.first()[SESSION_KEY]
        } catch (e: Exception) {
            // The corruption handler covers a malformed proto; this catches
            // anything else the storage layer can throw. Failing to read a
            // credential must never be fatal.
            Log.w(TAG, "Credential store is unreadable", e)
            return null
        } ?: return null

        val session = try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            json.decodeFromString<StoredSession>(String(KeystoreCrypto.decrypt(keyAlias, blob)))
        } catch (e: KeystoreCrypto.UndecryptableException) {
            // Expected after a device-to-device restore: the ciphertext travels,
            // the keystore key does not.
            Log.i(TAG, "Stored credential is unreadable; will re-authenticate", e)
            clear()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Stored credential is malformed; discarding", e)
            clear()
            return null
        }

        if (!session.isValid()) {
            Log.i(TAG, "Stored credential has expired")
            clear()
            return null
        }
        return session
    }

    /** True when a usable credential exists. Cheap enough to call before syncing. */
    suspend fun hasValidSession(): Boolean = load() != null

    /**
     * Forgets the credential.
     *
     * Deletes the ciphertext but keeps the keystore key: the key is per-install,
     * not per-session, so destroying it would gain nothing and add a keygen to
     * the next sign-in.
     */
    suspend fun clear() {
        runCatching { store.edit { it.remove(SESSION_KEY) } }
            .onFailure { Log.w(TAG, "Could not clear the credential store", it) }
    }

    /** Full teardown, for sign-out-everywhere flows and tests. */
    suspend fun destroy() {
        clear()
        KeystoreCrypto.deleteKey(keyAlias)
    }

    companion object {
        private const val TAG = "CredentialStore"

        /**
         * Must stay in step with the backup exclusions in
         * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.
         * Backing up the ciphertext is pointless — the key cannot follow it — and
         * shipping a bearer token to a backup service is worth avoiding anyway.
         */
        const val STORE_NAME = "sankranthi_credentials"

        const val DEFAULT_KEY_ALIAS = "sankranthi.session.v1"

        private val SESSION_KEY = stringPreferencesKey("session_blob")

        /** The production instance, backed by the app's single credential file. */
        fun create(context: Context): CredentialStore =
            CredentialStore(context.applicationContext.credentialDataStore)
    }
}
