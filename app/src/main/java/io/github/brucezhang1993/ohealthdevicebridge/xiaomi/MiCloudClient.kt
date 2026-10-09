package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class MiCloudDevice(val did: String, val mac: String, val name: String, val model: String)

internal object MiCloudCrypto {
    fun nonce(millis: Long, random: ByteArray): String {
        require(random.size == 8)
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12).put(random).putInt((millis / 60000).toInt()).array())
    }
    fun signedNonce(security: String, nonce: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(security) + Base64.getDecoder().decode(nonce))
    fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        require(key.isNotEmpty())
        val s = IntArray(256) { it }; var j = 0
        for (i in s.indices) { j = (j + s[i] + (key[i % key.size].toInt() and 255)) and 255; val t = s[i]; s[i] = s[j]; s[j] = t }
        var i = 0; j = 0
        fun next(): Int { i = (i + 1) and 255; j = (j + s[i]) and 255; val t = s[i]; s[i] = s[j]; s[j] = t; return s[(s[i] + s[j]) and 255] }
        repeat(1024) { next() }
        return ByteArray(input.size) { (input[it].toInt() xor next()).toByte() }
    }
    fun signature(path: String, nonceKey: ByteArray, params: Map<String, String>): String {
        val text = (listOf("POST", path) + params.map { "${it.key}=${it.value}" } + Base64.getEncoder().encodeToString(nonceKey)).joinToString("&")
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()))
    }
    fun fields(path: String, data: String, security: String, nonce: String): Map<String, String> {
        val key = signedNonce(security, nonce)
        try {
            val plain = linkedMapOf("data" to data)
            plain["rc4_hash__"] = signature(path, key, plain)
            val encrypted = plain.mapValuesTo(linkedMapOf()) { Base64.getEncoder().encodeToString(rc4(key, it.value.toByteArray())) }
            encrypted["signature"] = signature(path, key, encrypted)
            encrypted["ssecurity"] = security; encrypted["_nonce"] = nonce
            return encrypted
        } finally { key.fill(0) }
    }
}

internal class MiCloudClient(
    private val session: MiHomeSession,
    private val cancelled: () -> Boolean = { false },
    private val transport: ((String, JSONObject) -> JSONObject)? = null,
) {
    private val deadline = System.nanoTime() + 60_000_000_000L
    private fun checkActive() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw MiHomeFailure("CANCELLED", "已取消读取")
        if (System.nanoTime() >= deadline) throw MiHomeFailure("TIMEOUT", "读取米家超时，请重试")
    }
    fun devices(): List<MiCloudDevice> {
        val homes = request("/v2/homeroom/gethome", JSONObject().put("fg", true).put("fetch_share", true).put("fetch_share_dev", true).put("limit", 300).put("app_ver", 7))
        val own = homes.optJSONArray("homelist") ?: JSONArray()
        if (own.length() >= 300 || homes.optBoolean("has_more")) throw MiHomeFailure("LIST_LIMIT", "米家家庭数量超出读取上限")
        val all = linkedSetOf<Pair<String, String>>()
        for (i in 0 until own.length()) all += own.getJSONObject(i).get("id").toString() to session.userId
        val shared = request("/v2/user/get_device_cnt", JSONObject().put("fetch_own", true).put("fetch_share", true))
            .optJSONObject("share")?.optJSONArray("share_family") ?: JSONArray()
        for (i in 0 until shared.length()) shared.getJSONObject(i).let { all += it.get("home_id").toString() to it.get("home_owner").toString() }
        if (all.size > 300) throw MiHomeFailure("LIST_LIMIT", "米家家庭数量超出读取上限")
        val found = linkedMapOf<String, MiCloudDevice>(); var total = 0
        for ((home, owner) in all) {
            var cursor = ""; val cursors = mutableSetOf<String>()
            for (page in 0 until 10) {
                val data = JSONObject().put("home_id", home.toLong()).put("home_owner", owner.toLong()).put("limit", 200).put("get_split_device", true).put("support_smart_home", true)
                if (cursor.isNotEmpty()) data.put("start_did", cursor)
                val result = request("/v2/home/home_device_list", data)
                val devices = result.optJSONArray("device_info") ?: JSONArray()
                total += devices.length()
                if (total > 2000) throw MiHomeFailure("LIST_LIMIT", "米家设备数量超出读取上限")
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    val model = XiaomiModels.cloudModel(d.optString("model")) ?: continue
                    val mac = d.optString("mac").uppercase(java.util.Locale.ROOT)
                    if (!mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) continue
                    val did = d.getString("did")
                    found[did] = MiCloudDevice(did, mac, d.optString("name").ifEmpty { XiaomiModels.names.getValue(model) }.take(100), model)
                }
                if (!result.optBoolean("has_more") && devices.length() < 200) break
                cursor = result.optString("next_start_did")
                if (page == 9 || cursor.isEmpty() || !cursors.add(cursor)) throw MiHomeFailure("LIST_LIMIT", "米家设备列表未完整返回，请稍后重试")
            }
        }
        return found.values.toList()
    }
    fun beaconKey(device: MiCloudDevice): ByteArray {
        val result = request("/v2/device/blt_get_beaconkey", JSONObject().put("did", device.did).put("pdid", 1))
        val key = result.optString("beaconkey")
        if (!key.matches(Regex("[0-9a-fA-F]{32}"))) throw MiHomeFailure("KEY_UNAVAILABLE", "该设备未返回 BLE Key，请确认米家绑定和设备访问权限")
        return MiHomeSessionCodec.hex(key)
    }
    private fun request(path: String, data: JSONObject): JSONObject {
        checkActive()
        val result = transport?.invoke(path, data) ?: networkRequest(path, data)
        checkActive()
        return result
    }
    private fun networkRequest(path: String, data: JSONObject): JSONObject {
        checkActive()
        val nonce = MiCloudCrypto.nonce(System.currentTimeMillis() + session.timeDiff, ByteArray(8).also(SecureRandom()::nextBytes))
        val fields = MiCloudCrypto.fields(path, data.toString(), session.security, nonce)
        val connection = URL(session.api + path).openConnection() as HttpURLConnection
        try {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1, 10_000).toInt()
            connection.apply {
                requestMethod = "POST"; connectTimeout = remaining; readTimeout = remaining
                instanceFollowRedirects = false; doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Android APP/com.xiaomi.mihome APPV/11.7.709")
                setRequestProperty("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
                setRequestProperty("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
                val cuser = session.cUserId.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }?.let { "; cUserId=$it" }.orEmpty()
                require(!session.serviceToken.contains(Regex("[\\r\\n;]")))
                setRequestProperty("Cookie", "userId=${session.userId}; serviceToken=${session.serviceToken}; yetAnotherServiceToken=${session.serviceToken}$cuser; channel=MI_APP_STORE")
            }
            val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw MiHomeFailure("SESSION_EXPIRED", "米家会话已失效或无访问权限，请打开米家刷新后重试")
            if (status != 200) throw MiHomeFailure("NETWORK", "米家云请求失败（HTTP $status）")
            val response = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 1024 * 1024) throw MiHomeFailure("LIST_LIMIT", "米家响应超出大小上限"); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            checkActive()
            val key = MiCloudCrypto.signedNonce(session.security, nonce)
            val json = try {
                // Errors may be returned as plaintext JSON by the gateway.
                val text = String(response, Charsets.UTF_8)
                JSONObject(if (text.trimStart().startsWith("{")) text else String(MiCloudCrypto.rc4(key, Base64.getMimeDecoder().decode(response)), Charsets.UTF_8))
            } finally { key.fill(0) }
            val code = json.optInt("code", -1)
            if (code in setOf(-3, -6, -10001, 401)) throw MiHomeFailure("SESSION_EXPIRED", "米家会话已过期，请打开米家刷新登录后重试")
            if (code != 0) throw MiHomeFailure("CLOUD_REJECTED", "米家拒绝请求（错误码 $code），请确认设备访问权限")
            return json.getJSONObject("result")
        } catch (error: MiHomeFailure) { throw error }
        catch (_: Exception) { throw MiHomeFailure("NETWORK", "无法完成米家云请求，请检查网络、米家登录状态或更新模块") }
        finally { connection.disconnect() }
    }
}
