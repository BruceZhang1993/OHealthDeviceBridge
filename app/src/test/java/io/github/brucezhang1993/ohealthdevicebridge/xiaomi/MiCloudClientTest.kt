package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiCloudClientTest {
    private val session = MiHomeSession("1", "", "fixture", "", "cn", 0)
    private fun devices(model: String = "yunmai.scales.ms104") = JSONObject("""{"device_info":[{"did":"blt.fixture","mac":"aa:bb:cc:dd:ee:ff","name":"测试秤","model":"$model"}]}""")
    @Test fun queriesOwnedAndSharedHomesDeduplicatesAndFiltersModels() {
        val calls = mutableListOf<String>()
        val client = MiCloudClient(session, transport = { path, data ->
            calls += path
            when (path) {
                "/v2/homeroom/gethome" -> JSONObject("""{"homelist":[{"id":123}]}""")
                "/v2/user/get_device_cnt" -> JSONObject("""{"share":{"share_family":[{"home_id":456,"home_owner":2}]}}""")
                "/v2/home/home_device_list" -> if (data.getLong("home_id") == 123L) devices() else devices("xiaomi.scales.ms110")
                else -> error("Unexpected endpoint")
            }
        })
        val found = client.devices()
        assertEquals(1, found.size); assertEquals("AA:BB:CC:DD:EE:FF", found.single().mac); assertEquals(XiaomiModels.S400, found.single().model)
        assertEquals(2, calls.count { it == "/v2/home/home_device_list" })
    }
    @Test fun cancelledSyncDoesNotCallCloud() {
        val client = MiCloudClient(session, cancelled = { true }, transport = { _, _ -> error("Must not call") })
        try { client.devices(); fail() } catch (e: MiHomeFailure) { assertEquals("CANCELLED", e.code) }
    }
    @Test fun repeatedPaginationCursorTerminatesInsteadOfLooping() {
        var pages = 0
        val client = MiCloudClient(session, transport = { path, _ -> when (path) {
            "/v2/homeroom/gethome" -> JSONObject("""{"homelist":[{"id":123}]}""")
            "/v2/user/get_device_cnt" -> JSONObject()
            else -> { pages++; devices().put("has_more", true).put("next_start_did", "same") }
        } })
        try { client.devices(); fail() } catch (e: MiHomeFailure) { assertEquals("LIST_LIMIT", e.code); assertEquals(2, pages) }
    }
    @Test fun beaconKeyIsNotTheDeviceTokenAndRequiresExactLength() {
        val device = MiCloudDevice("blt.fixture", "AA:BB:CC:DD:EE:FF", "秤", XiaomiModels.S400)
        val valid = MiCloudClient(session, transport = { path, data ->
            assertEquals("/v2/device/blt_get_beaconkey", path); assertEquals(device.did, data.getString("did")); assertEquals(1, data.getInt("pdid"))
            JSONObject().put("beaconkey", "000102030405060708090a0b0c0d0e0f")
        }).beaconKey(device)
        assertArrayEquals(ByteArray(16) { it.toByte() }, valid)
        for (result in listOf(JSONObject().put("token", "000102030405060708090a0b0c0d0e0f"), JSONObject().put("beaconkey", "abcd"))) {
            try { MiCloudClient(session, transport = { _, _ -> result }).beaconKey(device); fail() } catch (e: MiHomeFailure) { assertEquals("KEY_UNAVAILABLE", e.code) }
        }
    }
}
