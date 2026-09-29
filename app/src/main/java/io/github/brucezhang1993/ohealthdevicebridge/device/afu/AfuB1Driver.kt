package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import java.util.concurrent.ConcurrentHashMap

object AfuB1Driver:DeviceDriver{
    override val model=BridgeConstants.AFU_MODEL;private val sessions=ConcurrentHashMap<String,AfuGattSession>()
    override fun scan(context:Context,timeoutMs:Long,onDevice:(BridgeDevice)->Unit,onTimeout:()->Unit,onError:(Throwable)->Unit)=AfuBleScanner().scan(context,timeoutMs,onDevice,onTimeout,onError)
    @SuppressLint("MissingPermission") override fun verifyForBind(context:Context,mac:String,onSuccess:()->Unit,onError:(Throwable)->Unit){disconnect(mac);val device=bluetoothDevice(context,mac,onError)?:return;lateinit var session:AfuGattSession;session=AfuGattSession(context,device,AfuGattSession.Mode.BIND_VERIFY,null,{sessions.remove(mac,session);onSuccess()},null,null,{sessions.remove(mac,session);onError(it)});sessions[mac]=session;session.connect()}
    @SuppressLint("MissingPermission") override fun measure(context:Context,mac:String,profile:UserProfile,onLiveWeight:(Double)->Unit,onFinal:(MeasurementRecord)->Unit,onError:(Throwable)->Unit){disconnect(mac);val device=bluetoothDevice(context,mac,onError)?:return;lateinit var session:AfuGattSession;session=AfuGattSession(context,device,AfuGattSession.Mode.MEASURE,profile,null,onLiveWeight,onFinal,{sessions.remove(mac,session);onError(it)});sessions[mac]=session;session.connect()}
    override fun disconnect(mac:String){sessions.remove(mac)?.disconnect()}
    @SuppressLint("MissingPermission") private fun bluetoothDevice(context:Context,mac:String,onError:(Throwable)->Unit)=runCatching{val manager=context.getSystemService(BluetoothManager::class.java)?:error("BluetoothManager unavailable");manager.adapter?.getRemoteDevice(mac)?:error("Bluetooth adapter unavailable")}.onFailure(onError).getOrNull()
}
