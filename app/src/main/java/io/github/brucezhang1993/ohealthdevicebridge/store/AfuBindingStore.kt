package io.github.brucezhang1993.ohealthdevicebridge.store

import android.content.Context
import java.util.Locale

object AfuBindingStore {
    private const val PREFS = "ohealth_device_bridge"
    private const val BODY_FAT_DEVICE_TYPE = "afu_body_fat_device_type_v1"
    private const val UNSET_DEVICE_TYPE = Int.MIN_VALUE

    // Versioned account namespaces intentionally do not adopt ambiguous legacy MACs.
    private fun key(account: String) = "afu_bound_v2_$account"

    fun add(context: Context, account: String, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key(account), macs(context, account) + normalize(mac)).apply()
    }

    fun remove(context: Context, account: String, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key(account), macs(context, account) - normalize(mac)).apply()
    }

    fun contains(context: Context, account: String, mac: String?) =
        mac != null && normalize(mac) in macs(context, account)

    fun macs(context: Context, account: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key(account), emptySet()).orEmpty().map(::normalize).toSet()

    /**
     * Keep the host's real body-fat-scale device type for synthetic bound-device rows.
     * This value belongs to the host schema rather than an OPPO account, so it is shared
     * between account namespaces and refreshed whenever a native Boohee row/product is seen.
     */
    fun setBodyFatDeviceType(context: Context, deviceType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(BODY_FAT_DEVICE_TYPE, deviceType).apply()
    }

    fun bodyFatDeviceType(context: Context): Int? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(BODY_FAT_DEVICE_TYPE, UNSET_DEVICE_TYPE)
        return value.takeUnless { it == UNSET_DEVICE_TYPE }
    }

    fun normalize(mac: String) = mac.uppercase(Locale.ROOT)
}
