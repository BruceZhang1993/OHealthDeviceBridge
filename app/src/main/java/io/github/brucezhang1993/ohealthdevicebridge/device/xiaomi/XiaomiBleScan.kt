package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

internal class XiaomiBleScan {
    @SuppressLint("MissingPermission")
    fun start(context: Context, service: UUID, mac: String?, timeoutMs: Long, result: (ScanResult, ByteArray) -> Unit, expired: () -> Unit, failed: (Throwable) -> Unit): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        check(Looper.myLooper() == main.looper)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) { failed(IllegalStateException("请开启蓝牙后重试")); return {} }
        var active = true
        lateinit var callback: ScanCallback
        lateinit var timeout: Runnable
        fun stop() {
            if (!active) return
            active = false; main.removeCallbacks(timeout)
            try { scanner.stopScan(callback) } catch (_: Exception) { /* Adapter may already have been disabled. */ }
        }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scan: ScanResult) { main.post {
                if (!active) return@post
                try {
                    if (mac != null && !scan.device.address.equals(mac, true)) return@post
                    val data = scan.scanRecord?.getServiceData(ParcelUuid(service)) ?: return@post
                    result(scan, data.copyOf())
                } catch (error: Exception) { stop(); failed(error) }
            } }
            override fun onScanFailed(errorCode: Int) { main.post { if (active) { stop(); failed(IllegalStateException("蓝牙扫描失败（$errorCode）")) } } }
        }
        timeout = Runnable { if (active) { stop(); expired() } }
        try {
            val filter = ScanFilter.Builder().setServiceData(ParcelUuid(service), byteArrayOf()).apply { if (mac != null) setDeviceAddress(mac) }.build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            main.postDelayed(timeout, timeoutMs)
        } catch (error: Exception) { stop(); failed(error) }
        return ::stop
    }
    companion object {
        fun uuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
        val MIBEACON = uuid(0xfe95)
    }
}
