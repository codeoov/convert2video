package com.example.convert2video.billing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** DataStore name `entitlement` produces the file `entitlement.preferences_pb`. */
internal val Context.entitlementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "entitlement",
)

internal object EntitlementStoreSynchronization {
    val mutex = Mutex()
}

internal data class PendingPurchase(
    val store: String,
    val purchaseToken: String,
) {
    init {
        require(isCanonicalBillingStore(store)) { "Unsupported billing store" }
        require(purchaseToken.isNotBlank()) { "Purchase token must not be blank" }
    }
}

internal data class Snapshot(
    val jwt: String?,
    val expiresAtEpochSeconds: Long?,
    val isActive: Boolean,
    val pendingPurchases: List<PendingPurchase>,
)

internal interface EntitlementStore {
    suspend fun read(): Snapshot

    suspend fun commit(
        jwt: String?,
        expiresAtEpochSeconds: Long?,
        isActive: Boolean,
    )

    /**
     * Atomically stores a backend-verified entitlement and removes the processed purchase pair.
     * Required order: provider [OwnedPurchase] is [PurchaseState.Purchased], backend verify
     * succeeds, then this method performs the single edit. Pending purchases are storage/retry
     * only and are rejected here. Repeating this operation is restart-idempotent.
     */
    suspend fun commitVerifiedEntitlement(
        jwt: String,
        expiresAtEpochSeconds: Long,
        isActive: Boolean,
        processedPurchase: OwnedPurchase,
    )

    suspend fun upsertPendingPurchase(purchase: PendingPurchase)

    suspend fun removePendingPurchase(purchase: PendingPurchase)
}

/** Keystore-backed entitlement storage; DataStore contains encrypted secret payloads only. */
internal class AndroidKeystoreEntitlementStore(
    context: Context,
) : EntitlementStore {
    private val appContext = context.applicationContext

    override suspend fun read(): Snapshot = withContext(Dispatchers.IO) {
        EntitlementStoreSynchronization.mutex.withLock { readUnlocked() }
    }

    override suspend fun commit(
        jwt: String?,
        expiresAtEpochSeconds: Long?,
        isActive: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            EntitlementStoreSynchronization.mutex.withLock {
                require(jwt == null || jwt.isNotBlank()) { "JWT must not be blank" }
                if (jwt != null) {
                    require(expiresAtEpochSeconds != null && expiresAtEpochSeconds > 0L) {
                        "JWT expiry must be a positive epoch second"
                    }
                }
                val normalizedExpiry = if (jwt == null) null else expiresAtEpochSeconds
                val normalizedActive = jwt != null &&
                    normalizedExpiry != null &&
                    normalizedExpiry > currentEpochSeconds() &&
                    isActive
                val encryptedJwt = jwt?.let { encrypt(it, JWT_KEY_ALIAS) }
                appContext.entitlementDataStore.edit { preferences ->
                    if (encryptedJwt == null) {
                        preferences.remove(KEY_JWT_CIPHERTEXT)
                    } else {
                        preferences[KEY_JWT_CIPHERTEXT] = encryptedJwt
                    }
                    if (normalizedExpiry == null) {
                        preferences.remove(KEY_EXPIRES_AT_EPOCH_SECONDS)
                    } else {
                        preferences[KEY_EXPIRES_AT_EPOCH_SECONDS] = normalizedExpiry
                    }
                    preferences[KEY_IS_ACTIVE] = normalizedActive
                }
            }
        }
    }

    override suspend fun commitVerifiedEntitlement(
        jwt: String,
        expiresAtEpochSeconds: Long,
        isActive: Boolean,
        processedPurchase: OwnedPurchase,
    ) {
        withContext(Dispatchers.IO) {
            EntitlementStoreSynchronization.mutex.withLock {
                require(jwt.isNotBlank()) { "JWT must not be blank" }
                require(expiresAtEpochSeconds > 0L) {
                    "JWT expiry must be a positive epoch second"
                }
                require(processedPurchase.state == PurchaseState.Purchased) {
                    "Only a Purchased purchase may commit entitlement"
                }
                val current = readUnlocked()
                val remainingPending = current.pendingPurchases
                    .filterNot {
                        it.store == processedPurchase.store &&
                            it.purchaseToken == processedPurchase.purchaseToken
                    }
                val encryptedJwt = encrypt(jwt, JWT_KEY_ALIAS)
                val encryptedPending = encryptedPendingPayload(remainingPending)
                val normalizedActive = isActive && expiresAtEpochSeconds > currentEpochSeconds()
                appContext.entitlementDataStore.edit { preferences ->
                    preferences[KEY_JWT_CIPHERTEXT] = encryptedJwt
                    preferences[KEY_EXPIRES_AT_EPOCH_SECONDS] = expiresAtEpochSeconds
                    preferences[KEY_IS_ACTIVE] = normalizedActive
                    if (encryptedPending == null) {
                        preferences.remove(KEY_PENDING_CIPHERTEXT)
                    } else {
                        preferences[KEY_PENDING_CIPHERTEXT] = encryptedPending
                    }
                }
            }
        }
    }

    override suspend fun upsertPendingPurchase(purchase: PendingPurchase) {
        withContext(Dispatchers.IO) {
            EntitlementStoreSynchronization.mutex.withLock {
                val current = readUnlocked()
                val next = current.pendingPurchases
                    .filterNot { it.sameIdentity(purchase) }
                    .plus(purchase)
                writePendingUnlocked(next)
            }
        }
    }

    override suspend fun removePendingPurchase(purchase: PendingPurchase) {
        withContext(Dispatchers.IO) {
            EntitlementStoreSynchronization.mutex.withLock {
                val current = readUnlocked()
                val next = current.pendingPurchases.filterNot { it.sameIdentity(purchase) }
                if (next.size != current.pendingPurchases.size) {
                    writePendingUnlocked(next)
                }
            }
        }
    }

    private suspend fun readUnlocked(): Snapshot {
        val preferences = appContext.entitlementDataStore.data.first()
        val jwt = preferences[KEY_JWT_CIPHERTEXT]?.let {
            decrypt(it, JWT_KEY_ALIAS).also { value ->
                require(value.isNotBlank()) { "Stored JWT must not be blank" }
            }
        }
        val expiry = preferences[KEY_EXPIRES_AT_EPOCH_SECONDS]
        val storedActive = preferences[KEY_IS_ACTIVE] ?: false
        val pendingPurchases = preferences[KEY_PENDING_CIPHERTEXT]
            ?.let { decodePendingPurchases(decryptBytes(it, PENDING_KEY_ALIAS)) }
            ?: emptyList()
        val isActive = jwt != null &&
            expiry != null &&
            expiry > currentEpochSeconds() &&
            storedActive
        return Snapshot(jwt, expiry, isActive, pendingPurchases)
    }

    private suspend fun writePendingUnlocked(pendingPurchases: List<PendingPurchase>) {
        val encrypted = encryptedPendingPayload(pendingPurchases)
        appContext.entitlementDataStore.edit { preferences ->
            if (encrypted == null) {
                preferences.remove(KEY_PENDING_CIPHERTEXT)
            } else {
                preferences[KEY_PENDING_CIPHERTEXT] = encrypted
            }
        }
    }

    private fun encryptedPendingPayload(pendingPurchases: List<PendingPurchase>): String? {
        val normalized = pendingPurchases
            .fold(LinkedHashMap<Pair<String, String>, PendingPurchase>()) { result, purchase ->
                result[purchase.store to purchase.purchaseToken] = purchase
                result
            }
            .values
            .sortedWith(compareBy<PendingPurchase> { it.store }.thenBy { it.purchaseToken })
        val encrypted = if (normalized.isEmpty()) {
            null
        } else {
            encryptBytes(encodePendingPurchases(normalized), PENDING_KEY_ALIAS)
        }
        return encrypted
    }

    private fun encodePendingPurchases(purchases: List<PendingPurchase>): ByteArray {
        require(purchases.size <= MAX_PENDING_PURCHASES) { "Too many pending purchases" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(PENDING_FORMAT_VERSION)
            data.writeInt(purchases.size)
            purchases.forEach { purchase ->
                writeString(data, purchase.store)
                writeString(data, purchase.purchaseToken)
            }
        }
        return output.toByteArray()
    }

    private fun decodePendingPurchases(bytes: ByteArray): List<PendingPurchase> {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == PENDING_FORMAT_VERSION) { "Unsupported pending payload" }
            val count = data.readInt()
            require(count in 0..MAX_PENDING_PURCHASES) { "Invalid pending count" }
            val purchases = buildList(count) {
                repeat(count) {
                    add(PendingPurchase(readString(data), readString(data)))
                }
            }
            require(data.available() == 0) { "Trailing pending payload" }
            return purchases.fold(LinkedHashMap<Pair<String, String>, PendingPurchase>()) { result, purchase ->
                result[purchase.store to purchase.purchaseToken] = purchase
                result
            }.values.toList()
        }
    }

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_FIELD_BYTES) {
            "Invalid pending field"
        }
        require(decodeUtf8Strict(bytes) == value) { "Invalid UTF-16 pending field" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readString(data: DataInputStream): String {
        val size = data.readInt()
        require(size in 1..MAX_FIELD_BYTES) { "Invalid pending field size" }
        val bytes = ByteArray(size)
        data.readFully(bytes)
        return decodeUtf8Strict(bytes).also {
            require(it.isNotBlank()) { "Invalid pending field" }
        }
    }

    private fun PendingPurchase.sameIdentity(other: PendingPurchase): Boolean =
        store == other.store && purchaseToken == other.purchaseToken

    private fun encrypt(value: String, alias: String): String =
        encryptBytes(value.toByteArray(Charsets.UTF_8), alias)

    private fun decrypt(encoded: String, alias: String): String =
        decodeUtf8Strict(decryptBytes(encoded, alias))

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun encryptBytes(value: ByteArray, alias: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Invalid GCM IV length" }
        val ciphertext = cipher.doFinal(value)
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined)
        ciphertext.copyInto(combined, destinationOffset = iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptBytes(encoded: String, alias: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size >= GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            "Invalid entitlement payload"
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Invalid GCM IV length" }
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(alias),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun key(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BYTES = 16
        const val GCM_TAG_LENGTH_BITS = 128
        const val JWT_KEY_ALIAS = "com.example.convert2video.entitlement.jwt"
        const val PENDING_KEY_ALIAS = "com.example.convert2video.entitlement.pending"
        const val PENDING_FORMAT_VERSION = 1
        const val MAX_PENDING_PURCHASES = 256
        const val MAX_FIELD_BYTES = 4096
        val KEY_JWT_CIPHERTEXT = stringPreferencesKey("jwt_ciphertext")
        val KEY_EXPIRES_AT_EPOCH_SECONDS = longPreferencesKey("expires_at_epoch_seconds")
        val KEY_IS_ACTIVE = booleanPreferencesKey("is_active")
        val KEY_PENDING_CIPHERTEXT = stringPreferencesKey("pending_ciphertext")

        fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1_000L
    }
}
