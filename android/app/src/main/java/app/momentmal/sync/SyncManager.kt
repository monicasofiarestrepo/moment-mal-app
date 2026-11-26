package app.momentmal.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.momentmal.container
import app.momentmal.content.ContentSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.syncStore by preferencesDataStore("sync")

data class SyncState(
    val repo: String,
    val branch: String,
    val hasToken: Boolean,
    val lastSyncAt: Long?,
    val lastError: String?,
    val version: Int?,
)

/** GitHub content sync: settings, encrypted token, and running a sync. */
class SyncManager(private val context: Context) {
    private val store get() = context.syncStore
    val contentDir: File get() = File(context.filesDir, "content")

    val state: Flow<SyncState> = store.data.map {
        SyncState(
            repo = it[REPO] ?: DEFAULT_REPO,
            branch = it[BRANCH] ?: "main",
            hasToken = it[TOKEN] != null,
            lastSyncAt = it[LAST_SYNC],
            lastError = it[LAST_ERROR],
            version = it[VERSION],
        )
    }

    suspend fun setRepo(repo: String, branch: String) = store.edit {
        it[REPO] = repo.trim()
        it[BRANCH] = branch.trim().ifEmpty { "main" }
    }

    suspend fun setToken(token: String) = store.edit {
        if (token.isBlank()) it.remove(TOKEN) else it[TOKEN] = encrypt(token.trim())
    }

    /** Runs a sync. Returns a short German status line. Never throws. */
    suspend fun sync(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val p = store.data.first()
        val token = p[TOKEN]?.let { runCatching { decrypt(it) }.getOrNull() }
        if (token == null) return@withContext "Kein Token gespeichert".also { saveError(it) }
        val current = context.container.contentVersion()
        try {
            val r = ContentSync().sync(p[REPO] ?: DEFAULT_REPO, p[BRANCH] ?: "main", token, contentDir, current, force)
            store.edit {
                it[LAST_SYNC] = System.currentTimeMillis()
                it.remove(LAST_ERROR)
                it[VERSION] = r.version
            }
            if (r.updated) {
                context.container.invalidateContent()
                "Version ${r.version} installiert · ${r.notes} Notizen"
            } else "Schon aktuell (Version ${r.version})"
        } catch (e: ContentSync.SyncException) {
            e.message.orEmpty().also { saveError(it) }
        } catch (e: Exception) {
            "Fehler: ${e.message}".also { saveError(it) }
        }
    }

    /** Sync at most every [minIntervalMs] (used on app start). */
    suspend fun syncIfStale(minIntervalMs: Long = 6 * 60 * 60_000L) {
        val p = store.data.first()
        if (p[TOKEN] == null) return
        if (System.currentTimeMillis() - (p[LAST_SYNC] ?: 0) < minIntervalMs) return
        sync()
    }

    private suspend fun saveError(msg: String) = store.edit {
        it[LAST_ERROR] = msg
        it[LAST_SYNC] = System.currentTimeMillis()
    }

    // ---- token encryption with an Android Keystore key (never leaves the device) ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }

    companion object {
        const val DEFAULT_REPO = "monicasofiarestrepo/moment-mal-content"
        private const val KEY_ALIAS = "momentmal_github_token"
        private val REPO = stringPreferencesKey("repo")
        private val BRANCH = stringPreferencesKey("branch")
        private val TOKEN = stringPreferencesKey("token_enc")
        private val LAST_SYNC = longPreferencesKey("last_sync")
        private val LAST_ERROR = stringPreferencesKey("last_error")
        private val VERSION = intPreferencesKey("version")
    }
}
