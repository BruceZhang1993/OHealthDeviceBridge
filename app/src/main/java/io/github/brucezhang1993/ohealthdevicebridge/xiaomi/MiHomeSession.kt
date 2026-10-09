package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/** Secrets deliberately have no generated toString()/copy()/componentN(). */
internal class MiHomeSession(
    val userId: String, val cUserId: String, val serviceToken: String, val security: String,
    val region: String, val timeDiff: Long,
) {
    val sid get() = if (region == "cn") "mijia" else "xiaomiio"
    val api get() = if (region == "cn") "https://api.mijia.tech/app" else "https://$region.api.io.mi.com/app"
    val identity: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$userId:$region".toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class MiHomeFailure(val code: String, message: String) : Exception(message)

internal object MiHomeSessionCodec {
    // Storage-format IV, not an account secret. Observed in Mi Home 11.7.709.
    private val storageIv = byteArrayOf(100, 23, 84, 114, 72, 0, 4, 97, 73, 97, 2, 52, 84, 102, 18, 32)
    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.matches(Regex("[0-9a-fA-F]+")))
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun decrypt(encrypted: String, androidId: String): String {
        require(androidId.isNotEmpty())
        val key = hex(buildString { repeat(32) { append(androidId[it % androidId.length]) } })
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(storageIv))
                String(doFinal(Base64.getMimeDecoder().decode(encrypted)), Charsets.UTF_8)
            }
        } finally { key.fill(0) }
    }
    fun preferences(xml: ByteArray): Map<String, String> {
        require(xml.size <= 512 * 1024)
        val text = String(xml, Charsets.UTF_8)
        // SharedPreferences writes UTF-8 XML. Reject declarations before parsing, including on
        // Android's XML implementation which does not support Xerces security feature flags.
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true) && !text.contains('\u0000'))
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).getElementsByTagName("string")
        return (0 until nodes.length).associate { val node = nodes.item(it); node.attributes.getNamedItem("name").nodeValue to node.textContent }
    }
    fun region(prefs: Map<String, String>): String {
        val raw = prefs["server_new"].orEmpty().ifEmpty { prefs["server"].orEmpty() }
        if (raw.isEmpty()) return "cn" // Mi Home's own default when no region has been stored.
        val region = if (raw.startsWith("{")) JSONObject(raw).getString("machineCode") else when (raw) {
            "us_true" -> "us"
            "tw", "hk", "kr", "in", "tr", "us" -> "sg"
            else -> raw
        }
        if (region !in setOf("cn", "sg", "de", "us", "i2", "ru"))
            throw MiHomeFailure("CACHE_UNSUPPORTED", "米家地区格式不兼容，请更新模块")
        return region
    }
    fun parse(accountXml: ByteArray, identityXml: ByteArray, regionXml: ByteArray): MiHomeSession {
        try {
            val encrypted = preferences(accountXml)["mi_account_encrypt"].orEmpty()
            if (encrypted.isEmpty()) throw MiHomeFailure("NOT_LOGGED_IN", "请先在米家登录并绑定体脂秤")
            val id = preferences(identityXml)["android_id"].orEmpty()
            val region = region(preferences(regionXml))
            val json = JSONObject(decrypt(encrypted, id))
            val sid = if (region == "cn") "mijia" else "xiaomiio"
            val raw = json.getJSONObject("serviceTokens").optString(sid)
            if (raw.isEmpty()) throw MiHomeFailure("SESSION_EXPIRED", "米家会话不可用，请打开米家刷新登录后重试")
            val token = JSONObject(raw)
            val user = json.getString("userId")
            val service = token.getString("serviceToken")
            val security = token.getString("ssecurity")
            require(user.matches(Regex("[0-9]+")) && service.isNotBlank() && Base64.getDecoder().decode(security).size == 16)
            return MiHomeSession(user, token.optString("cUserId"), service, security, region, token.optLong("timeDiff"))
        } catch (error: MiHomeFailure) { throw error }
        catch (_: Exception) { throw MiHomeFailure("CACHE_UNSUPPORTED", "米家登录缓存格式不兼容，请更新模块") }
    }
}
