package com.example.convert2video.desktopsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.convert2video.utils.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.pairingTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pairing_token",
)

/** Storage seam for the single active desktop bearer token. */
internal interface PairingTokenStore {
    suspend fun readToken(): String?

    suspend fun replaceToken(token: String)

    suspend fun clearToken()

    suspend fun storeDeviceName(name: String)

    suspend fun readDeviceName(): String?

    suspend fun clearDeviceName()
}

/** Keystore-backed token storage; the DataStore contains ciphertext only. */
internal class AndroidKeystorePairingTokenStore(
    context: Context,
) : PairingTokenStore {

    private val appContext = context.applicationContext

    override suspend fun readToken(): String? = withContext(Dispatchers.IO) {
        val encoded = appContext.pairingTokenDataStore.data.first()[TOKEN_CIPHERTEXT] ?: return@withContext null
        try {
            decrypt(encoded)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing token decrypt failed", error)
            null
        }
    }

    override suspend fun replaceToken(token: String) {
        withContext(Dispatchers.IO) {
            require(token.isNotBlank()) { "Pairing token must not be blank" }
            val encoded = encrypt(token)
            appContext.pairingTokenDataStore.edit { preferences ->
                preferences[TOKEN_CIPHERTEXT] = encoded
            }
        }
    }

    override suspend fun clearToken() {
        withContext(Dispatchers.IO) {
            appContext.pairingTokenDataStore.edit { preferences ->
                preferences.remove(TOKEN_CIPHERTEXT)
            }
        }
    }

    override suspend fun storeDeviceName(name: String) {
        require(name.isNotBlank()) { "Device name must not be blank; use clearDeviceName() to remove" }
        withContext(Dispatchers.IO) {
            appContext.pairingTokenDataStore.edit { preferences ->
                preferences[DEVICE_NAME] = name
            }
        }
    }

    override suspend fun readDeviceName(): String? = withContext(Dispatchers.IO) {
        val v = appContext.pairingTokenDataStore.data.first()[DEVICE_NAME] ?: return@withContext null
        v.ifBlank { null }
    }

    override suspend fun clearDeviceName() {
        withContext(Dispatchers.IO) {
            appContext.pairingTokenDataStore.edit { preferences ->
                preferences.remove(DEVICE_NAME)
            }
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined, destinationOffset = 0)
        ciphertext.copyInto(combined, destinationOffset = iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH_BYTES) { "Invalid pairing token payload" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "PairingTokenStore"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "convert2video.desktop_pairing_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        val TOKEN_CIPHERTEXT = stringPreferencesKey("token_ciphertext")
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
