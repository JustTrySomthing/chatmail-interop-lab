package app.p2p

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "records")
data class Record(
    @PrimaryKey val key: String,
    val kind: String,
    val payload: String,
    val created: Long = System.currentTimeMillis()
) {
    fun data() = JSONObject(payload)
}

@Dao
interface Records {
    @Query("""
        SELECT * FROM records WHERE kind IN (
            'contact', 'invite-in', 'invite-out', 'invite-pending', 'profile', 'group', 'folder',
            'message', 'message-reaction', 'post', 'social-action', 'file-sent', 'file-received',
            'appearance', 'thread-pref', 'peer-profile', 'call-history'
        ) ORDER BY created DESC
    """)
    fun observe(): Flow<List<Record>>

    @Query("SELECT * FROM records WHERE key = :key")
    suspend fun get(key: String): Record?

    @Query("SELECT * FROM records WHERE key = :key")
    fun observeRecord(key: String): Flow<Record?>

    @Query("SELECT * FROM records WHERE kind IN ('message', 'file-sent', 'file-received')")
    suspend fun threadItems(): List<Record>

    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY created ASC")
    suspend fun all(kind: String): List<Record>

    @Query("""
        SELECT * FROM records WHERE kind IN (
            'profile', 'contact', 'group', 'device', 'message', 'message-reaction',
            'file-sent', 'file-received', 'folder', 'thread-pref', 'peer-profile',
            'post', 'social-action', 'call-history'
        ) ORDER BY created ASC, key ASC
    """)
    suspend fun recoveryRecords(): List<Record>

    @Query("SELECT COUNT(*) FROM records WHERE kind NOT IN ('profile', 'discovery-peer', 'appearance', 'thread-pref', 'chatmail-cursor')")
    suspend fun restoreConflicts(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: Record)

    @Query("DELETE FROM records WHERE key = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM records WHERE kind = 'call-history'")
    suspend fun clearCallHistory()
}

@Database(entities = [Record::class], version = 1, exportSchema = false)
abstract class LocalStore : RoomDatabase() {
    abstract fun records(): Records

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, LocalStore::class.java, "p2p.db").build()
    }
}

object NativeEngine {
    init { System.loadLibrary("p2p_engine") }

    @JvmStatic external fun newIdentity(): String
    @JvmStatic external fun identityAddress(encoded: String): String
    @JvmStatic external fun stop()
    @JvmStatic external fun start(key: String, port: Int, addresses: String): String
    @JvmStatic external fun sign(payload: String): String
    @JvmStatic external fun signWith(root: String, payload: String): String
    @JvmStatic external fun verify(payload: String, peer: String, public: String, signature: String): Boolean
    @JvmStatic external fun submit(command: String): Boolean
    @JvmStatic external fun poll(): String
}

internal fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

internal fun JSONObject.strings(key: String): List<String> {
    val values = optJSONArray(key) ?: return emptyList()
    return (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
}

internal fun newId() = UUID.randomUUID().toString()
internal fun isId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun isPeer(value: String) = value.length in 25..96 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
internal fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
internal fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
internal fun randomToken() = encode(ByteArray(24).also(SecureRandom()::nextBytes))
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun jsonArray(values: Collection<String>) = JSONArray(values)

class IdentityVault(context: Context) {
    private val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "app.p2p.identity.v1"

    fun load(): String {
        val saved = preferences.getString("sealed", null)
        if (saved != null) return open(saved)
        return JSONObject(NativeEngine.newIdentity()).getString("secret").also(::save)
    }

    fun save(secret: String) {
        val sealed = seal(secret)
        check(preferences.edit().putString("sealed", sealed).commit())
    }

    fun pendingRecovery(): String? = preferences.getString("pending-recovery", null)?.let(::open)

    fun stageRecovery(data: String) {
        check(preferences.edit().putString("pending-recovery", seal(data)).commit())
    }

    fun finishRecovery(secret: String) {
        check(preferences.edit().putString("sealed", seal(secret)).remove("pending-recovery").commit())
    }

    private fun open(value: String): String {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: error("Identity protection key is missing")
        val bytes = decode(value)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    private fun seal(secret: String): String {
        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            iv + doFinal(secret.toByteArray(Charsets.UTF_8))
        }
        return encode(sealed)
    }
}

internal object Rendezvous {
    private fun derive(secret: String, label: String): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(decode(secret), "HmacSHA256"))
        doFinal(label.toByteArray(Charsets.UTF_8))
    }

    fun key(secret: String, peer: String) = derive(secret, "p2p-rendezvous-key-v1:" + peer).hex()

    fun seal(secret: String, payload: String): String {
        val key = javax.crypto.spec.SecretKeySpec(derive(secret, "p2p-rendezvous-value-v1"), "AES")
        return encode(Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            iv + doFinal(payload.toByteArray(Charsets.UTF_8))
        })
    }

    fun open(secret: String, envelope: String): String {
        require(envelope.length in 38..4000)
        val bytes = decode(envelope)
        val key = javax.crypto.spec.SecretKeySpec(derive(secret, "p2p-rendezvous-value-v1"), "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
}

internal fun remainingMessageTargets(data: JSONObject): Int =
    (data.optInt("targets", 1) - data.optInt("delivered") - data.optInt("failed") - data.optInt("cancelled")).coerceAtLeast(0)

internal fun canCancelLegacyDelivery(data: JSONObject): Boolean =
    data.optInt("nativeMessage") <= 9 && remainingMessageTargets(data) > 0

internal fun messageDeliveryStatus(data: JSONObject): String {
    val delivered = data.optInt("delivered")
    val targets = data.optInt("targets", 1)
    val cancelled = data.optInt("cancelled")
    if (cancelled > 0) {
        val cancellation = if (data.optBoolean("possiblySent")) "Retries canceled" else "Canceled"
        return if (delivered > 0) "Delivered to $delivered of $targets · ${cancellation.lowercase()}" else cancellation
    }
    return when {
        delivered == 0 && remainingMessageTargets(data) == 0 -> "Not delivered"
        delivered == 0 && data.optBoolean("chatmailSubmitted") -> CHATMAIL_SUBMITTED
        delivered == 0 -> "Queued"
        targets > 1 -> "Delivered to $delivered of $targets"
        else -> "Delivered"
    }
}
