package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.*

class OppoBodyComposition(private val classLoader:ClassLoader,private val factory:OppoObjectFactory){
    fun prepare(manager:Any,userTag:String?){OppoReflect.tryCall(manager,"ensureInit");if(!userTag.isNullOrBlank())OppoReflect.tryCall(manager,"refreshUserModelToSdk",userTag)}
    fun currentUserProfile(manager:Any):UserProfile{val user=findUserModel(manager);val age=number(user,"getAge","age")?.toInt()?.takeIf{it in 5..120}?:30;if(user==null)return UserProfile(age,true,60.0);val sexValue=OppoReflect.read(user,"getSex","sex");val male=when(sexValue){is Number->sexValue.toInt()==1;is Boolean->sexValue;is String->sexValue.equals("male",true)||sexValue=="1"||sexValue=="男";else->true};val target=number(user,"getWeight","weight")?.toDouble()?.takeIf{it>0}?:60.0;return UserProfile(age,male,target,1)}
    fun buildScaleModel(manager:Any,mac:String,record:MeasurementRecord):Any{val clazz=XposedHelpers.findClass(OppoObjectFactory.BH_SCALE_MODEL,classLoader);val model=XposedHelpers.newInstance(clazz);OppoReflect.tryCall(model,"setWeight",record.weightKg.toFloat());OppoReflect.tryCall(model,"setBodyResistance",(record.resistanceOhm?:0).toFloat());OppoReflect.tryCall(model,"setSecond",record.timestampEpochSeconds);OppoReflect.tryCall(model,"setHistory",false);OppoReflect.tryCall(model,"setLockData",true);OppoReflect.tryCall(model,"setDeviceModel",factory.createBhDevice(mac));if(record.resistanceOhm==null||record.resistanceOhm<=0)return model;val bh=findScaleManager(manager)?:return model;val calculated=runCatching{XposedHelpers.callMethod(bh,"handleTheHistoryScaleModelDetailByScaleModel",model)}.onFailure{BridgeLog.e("OPPO ICOMON calculation failed; returning raw BHScaleModel",it)}.getOrNull();return if(calculated!=null&&clazz.isInstance(calculated))calculated else model}
    private fun findScaleManager(manager:Any)=OppoReflect.fieldByType(manager,OppoObjectFactory.BH_SCALE_MANAGER)?:OppoReflect.read(manager,"getManager","manager")
    private fun findUserModel(manager:Any):Any?{OppoReflect.fieldByType(manager,OppoObjectFactory.BH_USER_MODEL)?.let{return it};val scale=findScaleManager(manager)?:return null;OppoReflect.fieldByType(scale,OppoObjectFactory.BH_USER_MODEL)?.let{return it};OppoReflect.fields(scale).forEach{(_,n)->if(n!=null)OppoReflect.fieldByType(n,OppoObjectFactory.BH_USER_MODEL)?.let{return it}};return null}
    private fun number(instance:Any?,vararg names:String)=instance?.let{OppoReflect.read(it,*names) as? Number}
}
