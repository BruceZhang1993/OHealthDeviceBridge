package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import javax.crypto.spec.SecretKeySpec

class MiCredentialStoreTest {
    private val values = mutableMapOf<String, String>()
    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { values[args!![0] as String] = args[1] as String; editor }
                    "remove" -> { values.remove(args!![0]); editor }
                    "clear" -> { values.clear(); editor }
                    "commit" -> true
                    else -> editor
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) { "edit" -> editor; "getString" -> values[args!![0]] ?: args[1]; else -> null }
            } as SharedPreferences
        }
    }
    private val device = MiCloudDevice("fixture", "AA:BB:CC:DD:EE:FF", "测试秤", XiaomiModels.S400)
    private val key = ByteArray(16) { it.toByte() }
    private fun store() = MiCredentialStore(context) { SecretKeySpec(ByteArray(16) { (it + 1).toByte() }, "AES") }
    @Test fun encryptedCredentialSurvivesNewInstanceButIsIsolatedByAccount() {
        store().invalidateIdentity("mi-A-cn")
        store().put("oppo-A", MiCredential(device, key, "mi-A-cn"))
        assertFalse(values.values.any { it.contains("fixture") || it.contains("测试秤") })
        assertArrayEquals(key, store().get("oppo-A", device.mac.lowercase(), "mi-A-cn")!!.key)
        assertNull(store().get("oppo-B", device.mac, "mi-A-cn"))
        store().remove("oppo-A", device.mac); assertNull(store().get("oppo-A", device.mac, "mi-A-cn"))
    }
    @Test fun accountOrRegionChangeRevokesOldCache() {
        store().invalidateIdentity("mi-A-cn")
        store().put("oppo-A", MiCredential(device, key, "mi-A-cn"))
        store().invalidateIdentity("mi-A-de")
        assertNull(store().get("oppo-A", device.mac, "mi-A-cn"))
    }
    @Test fun tamperingAndChangedIdentityNeverReleaseKey() {
        store().put("oppo-A", MiCredential(device, key, "mi-A-cn"))
        val entry = values.keys.single()
        values[entry] = values.getValue(entry).dropLast(5) + "AAAAA"
        assertNull(store().get("oppo-A", device.mac, "mi-A-cn"))
        store().put("oppo-A", MiCredential(device, key, "mi-A-cn"))
        assertNull(store().get("oppo-A", device.mac, "mi-B-cn"))
    }
}
