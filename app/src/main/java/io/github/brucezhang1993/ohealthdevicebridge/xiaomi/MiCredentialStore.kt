package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class MiCredential(val device: MiCloudDevice, val key: ByteArray, val identity: String)

internal class MiCredentialStore(context: Context, private val keySource: (() -> SecretKey)? = null) {
    private val prefs = context.getSharedPreferences("xiaomi_credentials", Context.MODE_PRIVATE)
    private fun key(account: String, mac: String) = MessageDigest.getInstance("SHA-256").digest("$account:${mac.uppercase(java.util.Locale.ROOT)}".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun secret(): SecretKey = synchronized(keyLock) {
        keySource?.let { return@synchronized it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("xiaomi_ble_keys", null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("xiaomi_ble_keys", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(account: String, credential: MiCredential) {
        val d = credential.device
        val json = JSONObject().put("did", d.did).put("mac", d.mac).put("name", d.name).put("model", d.model)
            .put("identity", credential.identity).put("key", Base64.getEncoder().encodeToString(credential.key)).toString().toByteArray()
        val storageKey = key(account, d.mac)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()); updateAAD(storageKey.toByteArray()) }
        val encrypted = try { cipher.doFinal(json) } finally { json.fill(0) }
        check(prefs.edit().putString(storageKey, Base64.getEncoder().encodeToString(cipher.iv + encrypted)).commit()) { "无法保存体脂秤凭证" }
    }
    @Synchronized fun get(account: String, mac: String, identity: String): MiCredential? {
        val storageKey = key(account, mac)
        val stored = prefs.getString(storageKey, null) ?: return null
        try {
            val data = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, data.copyOfRange(0, 12))); updateAAD(storageKey.toByteArray())
            }
            val bytes = cipher.doFinal(data.copyOfRange(12, data.size))
            val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
            if (json.getString("identity") != identity) { remove(account, mac); return null }
            return MiCredential(MiCloudDevice(json.getString("did"), json.getString("mac"), json.getString("name"), json.getString("model")), Base64.getDecoder().decode(json.getString("key")), identity)
        } catch (_: Exception) { remove(account, mac); return null }
    }
    @Synchronized fun invalidateIdentity(identity: String) {
        // All encrypted entries are small (one selected key per bound scale); no account sessions are stored.
        val previous = prefs.getString("active_identity", null)
        val edit = prefs.edit()
        if (previous != null && previous != identity) edit.clear()
        check(edit.putString("active_identity", identity).commit()) { "无法更新体脂秤凭证关联" }
    }
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) }
    @Synchronized fun remove(account: String, mac: String) { check(prefs.edit().remove(key(account, mac)).commit()) }
    companion object { private val keyLock = Any() }
}
