package io.github.brucezhang1993.ohealthdevicebridge.store

import android.content.Context

object AfuBindingStore {
    private const val PREFS = "ohealth_device_bridge"
    private const val KEY_AFU_MACS = "afu_bound_macs"

    fun add(context: Context, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_AFU_MACS, prefs.getStringSet(KEY_AFU_MACS, emptySet()).orEmpty() + mac).apply()
    }

    fun remove(context: Context, mac: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_AFU_MACS, prefs.getStringSet(KEY_AFU_MACS, emptySet()).orEmpty() - mac).apply()
    }

    fun contains(context: Context, mac: String?): Boolean =
        mac != null && macs(context).any { it.equals(mac, ignoreCase = true) }

    fun macs(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_AFU_MACS, emptySet())
            ?.toSet()
            .orEmpty()
}
