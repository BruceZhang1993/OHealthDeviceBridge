package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MiCloudCryptoTest {
    @Test fun signedRc4RequestMatchesIndependentPythonVector() {
        val security = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val nonce = MiCloudCrypto.nonce(1700000000000, ByteArray(8) { it.toByte() })
        assertEquals("AAECAwQFBgcBsFUV", nonce)
        val fields = MiCloudCrypto.fields("/v2/device/blt_get_beaconkey", "{\"did\":\"blt.fixture\",\"pdid\":1}", security, nonce)
        assertEquals("8WZJUunKESy0xhV+FY9hIjyMhVxoJ1wikMYDVC+Y", fields["data"])
        assertEquals("wSlka7WNaWO34SsyBdZpZBnLhTh3YQcgkNdSUw==", fields["rc4_hash__"])
        assertEquals("OomBbU7pJfDaqMNFI0WHVVMb7ww=", fields["signature"])
        val key = MiCloudCrypto.signedNonce(security, nonce)
        assertEquals("{\"did\":\"blt.fixture\",\"pdid\":1}", String(MiCloudCrypto.rc4(key, Base64.getDecoder().decode(fields["data"]))))
    }
    @Test fun overseasAndChinaUseDifferentSessionScopes() {
        val cn = MiHomeSession("1", "", "fixture", "", "cn", 0)
        val de = MiHomeSession("1", "", "fixture", "", "de", 0)
        assertEquals("xiaomiio", de.sid)
        assertEquals("https://de.api.io.mi.com/app", de.api)
        assertNotEquals(cn.identity, de.identity)
        assertNotEquals(cn.identity, MiHomeSession("2", "", "fixture", "", "cn", 0).identity)
    }
}
