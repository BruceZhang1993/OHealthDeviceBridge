package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import android.annotation.SuppressLint
import android.bluetooth.*
import android.os.Build
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import java.util.ArrayDeque

class AfuGattQueue(private val gattProvider:()->BluetoothGatt?,private val onFatalError:(Throwable)->Unit){
    private sealed interface Operation{val description:String;val onSuccess:(()->Unit)?;data class DescriptorWrite(val descriptor:BluetoothGattDescriptor,val value:ByteArray,override val description:String,override val onSuccess:(()->Unit)?):Operation;data class CharacteristicWrite(val characteristic:BluetoothGattCharacteristic,val value:ByteArray,override val description:String,override val onSuccess:(()->Unit)?):Operation}
    private val queue=ArrayDeque<Operation>();private var active:Operation?=null
    @Synchronized fun enqueueDescriptor(descriptor:BluetoothGattDescriptor,value:ByteArray,description:String,onSuccess:(()->Unit)?=null){queue+=Operation.DescriptorWrite(descriptor,value.copyOf(),description,onSuccess);drainLocked()}
    @Synchronized fun enqueueWrite(characteristic:BluetoothGattCharacteristic,value:ByteArray,description:String,onSuccess:(()->Unit)?=null){queue+=Operation.CharacteristicWrite(characteristic,value.copyOf(),description,onSuccess);drainLocked()}
    @Synchronized fun onDescriptorWrite(descriptor:BluetoothGattDescriptor,status:Int){val op=active as? Operation.DescriptorWrite?:return;if(op.descriptor.uuid!=descriptor.uuid)return;finishLocked(status,op)}
    @Synchronized fun onCharacteristicWrite(characteristic:BluetoothGattCharacteristic,status:Int){val op=active as? Operation.CharacteristicWrite?:return;if(op.characteristic.uuid!=characteristic.uuid)return;finishLocked(status,op)}
    @Synchronized fun clear(){queue.clear();active=null}
    private fun finishLocked(status:Int,op:Operation){active=null;if(status!=BluetoothGatt.GATT_SUCCESS){queue.clear();onFatalError(IllegalStateException("GATT operation failed status=$status op=${op.description}"));return};runCatching{op.onSuccess?.invoke()}.onFailure(onFatalError);drainLocked()}
    @SuppressLint("MissingPermission") private fun drainLocked(){if(active!=null)return;val gatt=gattProvider()?:return;val op=if(queue.isEmpty())return else queue.removeFirst();active=op;val started=when(op){is Operation.DescriptorWrite->if(Build.VERSION.SDK_INT>=33)gatt.writeDescriptor(op.descriptor,op.value)==BluetoothGatt.GATT_SUCCESS else {@Suppress("DEPRECATION") op.descriptor.value=op.value;@Suppress("DEPRECATION") gatt.writeDescriptor(op.descriptor)};is Operation.CharacteristicWrite->if(Build.VERSION.SDK_INT>=33)gatt.writeCharacteristic(op.characteristic,op.value,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothGatt.GATT_SUCCESS else {@Suppress("DEPRECATION") op.characteristic.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;@Suppress("DEPRECATION") op.characteristic.value=op.value;@Suppress("DEPRECATION") gatt.writeCharacteristic(op.characteristic)}};if(!started){active=null;queue.clear();BridgeLog.e("GATT operation could not start: ${op.description}");onFatalError(IllegalStateException("GATT operation could not start: ${op.description}"))}}
}
