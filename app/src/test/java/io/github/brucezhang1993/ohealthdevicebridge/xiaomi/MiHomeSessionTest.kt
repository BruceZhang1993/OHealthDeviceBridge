package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import org.junit.Assert.*
import org.junit.Test

class MiHomeSessionTest {
    // Independent PyCryptodome AES-CBC fixture. Contains synthetic account data only.
    private val encrypted = "9XuFIXPq+Xnn6UTo1MfDl8pI6z6ssYHpqejSTkcUVjHGvhBZU6SVOEIdQjwH+Ij9zc/DOPQAigzxnAi0dTcUyLDwgodYmvWRrSQsy/ldL7aSuudgtNlyYoPfKErr4SR+jUtNm06Ub6yBX9cx6Wc1kJcrBDsnsWUurmDgQSKIY3Gfbyk58v34dglmnVB142Wdbwozo/TC8+CnQ397Vl/G3DQ7QHrVtptKlfIus+JM2MNow3lCRtPbo5yCtCH2ay3i3woNsFFx0CReFNTrXcQ57Q=="
    private fun xml(key: String, value: String) = "<map><string name=\"$key\">$value</string></map>".toByteArray()
    @Test fun decryptsWithMiHomeAndroidIdAndParsesStringEncodedToken() {
        val decoded = MiHomeSessionCodec.decrypt(encrypted, "0123456789abcdef")
        val account = org.json.JSONObject(decoded)
        assertEquals("123456789", account.getString("userId"))
        assertEquals("synthetic-test-token", org.json.JSONObject(account.getJSONObject("serviceTokens").getString("mijia")).getString("serviceToken"))
        val session = MiHomeSessionCodec.parse(xml("mi_account_encrypt", encrypted), xml("android_id", "0123456789abcdef"), "<map/>".toByteArray())
        assertEquals("123456789", session.userId)
        assertEquals("synthetic-test-token", session.serviceToken)
        assertEquals("mijia", session.sid)
        assertEquals("https://api.mijia.tech/app", session.api)
        assertFalse(session.toString().contains(session.serviceToken))
    }
    @Test fun wrongAppAndroidIdCannotDecrypt() {
        try { MiHomeSessionCodec.parse(xml("mi_account_encrypt", encrypted), xml("android_id", "fedcba9876543210"), "<map/>".toByteArray()); fail() }
        catch (e: MiHomeFailure) { assertEquals("CACHE_UNSUPPORTED", e.code); assertFalse(e.message!!.contains(encrypted)) }
    }
    @Test fun missingLoginHasActionableError() {
        try { MiHomeSessionCodec.parse("<map/>".toByteArray(), "<map/>".toByteArray(), "<map/>".toByteArray()); fail() }
        catch (e: MiHomeFailure) { assertEquals("NOT_LOGGED_IN", e.code) }
    }
    @Test fun regionPreservesServerMachineCodeAndRejectsUnknownRoutes() {
        assertEquals("de", MiHomeSessionCodec.region(mapOf("server_new" to "{\"countryCode\":\"DE\",\"machineCode\":\"de\"}")))
        assertEquals("sg", MiHomeSessionCodec.region(mapOf("server" to "hk")))
        assertEquals("us", MiHomeSessionCodec.region(mapOf("server" to "us_true")))
        try { MiHomeSessionCodec.region(mapOf("server" to "evil.example")); fail() } catch (_: MiHomeFailure) { }
    }
    @Test fun xmlRejectsEntitiesAndUnboundedInput() {
        for (xml in listOf("<!DOCTYPE map [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><map/>", "<!ENTITY secret 'x'>")) {
            try { MiHomeSessionCodec.preferences(xml.toByteArray()); fail() } catch (_: IllegalArgumentException) { }
        }
        try { MiHomeSessionCodec.preferences(ByteArray(512 * 1024 + 1)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun providerOnlyAcceptsHostUidInSameAndroidUser() {
        assertTrue(MiHomeCallerPolicy.allowed(10123, 10123, 10456))
        assertFalse(MiHomeCallerPolicy.allowed(10124, 10123, 10456))
        assertFalse(MiHomeCallerPolicy.allowed(110123, 110123, 10456))
        assertFalse(MiHomeCallerPolicy.allowed(0, 10123, 10456))
    }
}
