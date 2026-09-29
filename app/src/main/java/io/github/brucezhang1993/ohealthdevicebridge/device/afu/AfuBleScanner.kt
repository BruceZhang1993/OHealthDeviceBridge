package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.BridgeDevice
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AfuBleScanner {
    private val mainHandler=Handler(Looper.getMainLooper())
    @SuppressLint("MissingPermission")
    fun scan(context:Context,timeoutMs:Long,onDevice:(BridgeDevice)->Unit,onTimeout:()->Unit,onError:(Throwable)->Unit){
        val manager=context.getSystemService(BluetoothManager::class.java); val adapter=manager?.adapter; val scanner=adapter?.bluetoothLeScanner
        if(adapter==null||!adapter.isEnabled||scanner==null){onError(IllegalStateException("Bluetooth is unavailable or disabled"));return}
        val finished=AtomicBoolean(false); lateinit var callback:ScanCallback
        val stop:(Boolean)->Unit={timedOut->if(finished.compareAndSet(false,true)){runCatching{scanner.stopScan(callback)};if(timedOut)onTimeout()}}
        callback=object:ScanCallback(){
            override fun onScanResult(callbackType:Int,result:ScanResult){val record=result.scanRecord?:return;val hasService=record.serviceUuids.orEmpty().any{it.uuid==SERVICE_UUID};val advertisedName=record.deviceName?:runCatching{result.device.name}.getOrNull();val isAfu=advertisedName?.contains("AFU",true)==true;if(!hasService||!isAfu)return;val mac=result.device.address;if(!finished.compareAndSet(false,true))return;runCatching{scanner.stopScan(this)};BridgeLog.i("AFU scan found ${BridgeLog.maskedMac(mac)} name=$advertisedName");onDevice(BridgeDevice(mac,advertisedName?:BridgeConstants.AFU_MODEL,BridgeConstants.AFU_MODEL))}
            override fun onScanFailed(errorCode:Int){if(!finished.compareAndSet(false,true))return;onError(IllegalStateException("BLE scan failed: $errorCode"))}
        }
        val filter=ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build();val settings=ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();BridgeLog.i("AFU scan start timeout=${timeoutMs}ms");runCatching{scanner.startScan(listOf(filter),settings,callback)}.onFailure(onError);mainHandler.postDelayed({stop(true)},timeoutMs)
    }
    companion object{val SERVICE_UUID:UUID=UUID.fromString("0000fc50-0000-1000-8000-00805f9b34fb")}
}
