package app.p2p

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal enum class GrantDecision { CURRENT, REPLACE, REJECT }

internal fun grantDecision(currentDevice: String, currentRevision: Long, nextDevice: String, nextRevision: Long): GrantDecision =
    when {
        nextRevision <= 0L || nextRevision < currentRevision -> GrantDecision.REJECT
        nextRevision > currentRevision -> GrantDecision.REPLACE
        nextDevice == currentDevice -> GrantDecision.CURRENT
        else -> GrantDecision.REJECT
    }

internal data class DeviceGrant(val account: String, val device: String, val revision: Long, val encoded: String) {
    companion object {
        fun issue(root: String, device: String, revision: Long): DeviceGrant {
            val account = NativeEngine.identityAddress(root)
            require(isPeer(account) && isPeer(device) && revision > 0L)
            val body = obj("purpose" to "p2p-device-v1", "account" to account, "device" to device, "revision" to revision).toString()
            val encoded = JSONObject(NativeEngine.signWith(root, body)).put("body", body).toString()
            return DeviceGrant(account, device, revision, encoded)
        }

        fun verify(encoded: String): DeviceGrant? {
            if (encoded.length !in 1..4096) return null
            return runCatching {
                val signed = JSONObject(encoded)
                val body = signed.getString("body")
                val claims = JSONObject(body)
                val account = claims.getString("account")
                val device = claims.getString("device")
                val revision = claims.getLong("revision")
                require(claims.optString("purpose") == "p2p-device-v1" && isPeer(account) && isPeer(device) && revision > 0L)
                require(NativeEngine.verify(body, account, signed.getString("public"), signed.getString("signature")))
                DeviceGrant(account, device, revision, encoded)
            }.getOrNull()
        }
    }
}

internal data class DeviceAccount(
    val grant: DeviceGrant,
    val deviceSecret: String,
    private val unbackedRoot: String = "",
    private val protectedRoot: String = ""
) {
    fun encode() = obj("grant" to grant.encoded, "deviceSecret" to deviceSecret, "unbackedRoot" to unbackedRoot,
        "protectedRoot" to protectedRoot).toString()

    fun recoverySecret(password: CharArray): String {
        if (unbackedRoot.isNotEmpty()) return unbackedRoot
        val raw = RecoveryCipher.decrypt(decode(protectedRoot), password)
        return try {
            String(raw, Charsets.UTF_8)
        } finally {
            raw.fill(0)
        }
    }

    fun protectRoot(root: String, password: CharArray): DeviceAccount {
        if (protectedRoot.isNotEmpty()) return this
        return copy(unbackedRoot = "", protectedRoot = lock(root, password))
    }

    companion object {
        fun read(encoded: String): DeviceAccount {
            val state = JSONObject(encoded)
            val grant = DeviceGrant.verify(state.getString("grant")) ?: error("Invalid device credential")
            val secret = state.getString("deviceSecret")
            val root = state.optString("unbackedRoot")
            val locked = state.optString("protectedRoot")
            require(NativeEngine.identityAddress(secret) == grant.device &&
                ((root.isNotEmpty() && locked.isEmpty() && NativeEngine.identityAddress(root) == grant.account) ||
                    (root.isEmpty() && locked.isNotEmpty()))) { "Invalid identity data" }
            return DeviceAccount(grant, secret, root, locked)
        }

        fun load(vault: IdentityVault): DeviceAccount {
            val saved = vault.load()
            if (saved.startsWith("{")) return read(saved)
            return create(saved, 1).also { vault.save(it.encode()) }
        }

        fun replacement(root: String, oldRevision: Long, password: CharArray): DeviceAccount {
            require(oldRevision in 1 until Long.MAX_VALUE)
            return create(root, maxOf(System.currentTimeMillis(), oldRevision + 1)).protectRoot(root, password)
        }

        private fun create(root: String, revision: Long): DeviceAccount {
            val identity = JSONObject(NativeEngine.newIdentity())
            return DeviceAccount(
                grant = DeviceGrant.issue(root, identity.getString("peer"), revision),
                deviceSecret = identity.getString("secret"),
                unbackedRoot = root
            )
        }

        private fun lock(root: String, password: CharArray): String {
            val raw = root.toByteArray(Charsets.UTF_8)
            return try { encode(RecoveryCipher.encrypt(raw, password)) } finally { raw.fill(0) }
        }
    }
}

internal class DeviceDirectory(private val records: Records) {
    private val known = ConcurrentHashMap<String, DeviceGrant>()
    private val cachedCertificates = ConcurrentHashMap<String, DeviceGrant>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun load() {
        known.clear()
        cachedCertificates.clear()
        records.all("device").forEach { row ->
            val grant = DeviceGrant.verify(row.data().optString("grant"))
            if (grant != null && row.key == "device:" + grant.account) known[grant.account] = grant
        }
    }

    fun find(account: String): DeviceGrant? = known[account]

    fun accountFor(device: String): String? = known.values.firstOrNull { it.device == device }?.account

    fun checked(encoded: String): DeviceGrant? {
        if (encoded.length > 4096) return null
        val current = cachedCertificates[encoded]
        if (current != null) return current
        val verified = DeviceGrant.verify(encoded) ?: return null
        if (cachedCertificates.size >= 128) cachedCertificates.clear()
        cachedCertificates[encoded] = verified
        return verified
    }

    suspend fun accept(candidate: DeviceGrant): Boolean {
        lock.lock()
        try {
            val current = known[candidate.account]
            val action = grantDecision(current?.device.orEmpty(), current?.revision ?: 0L, candidate.device, candidate.revision)
            if (action == GrantDecision.REJECT) return false
            if (action == GrantDecision.REPLACE) {
                records.put(Record("device:" + candidate.account, "device", obj("grant" to candidate.encoded).toString()))
                known[candidate.account] = candidate
            }
            return true
        } finally {
            lock.unlock()
        }
    }
}
