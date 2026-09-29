package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import android.app.AndroidAppHelper
import android.content.Context
import android.os.*
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import java.lang.reflect.*

object OppoReflect{
    private val main=Handler(Looper.getMainLooper());fun context():Context?=AndroidAppHelper.currentApplication();fun postMain(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())block()else main.post(block)}
    fun read(instance:Any,vararg names:String):Any?{names.forEach{name->runCatching{return XposedHelpers.callMethod(instance,name)};runCatching{return XposedHelpers.getObjectField(instance,name)}};return null}
    fun readString(instance:Any,vararg names:String)=read(instance,*names) as? String
    fun call(instance:Any,name:String,vararg args:Any?)=XposedHelpers.callMethod(instance,name,*args)
    fun tryCall(instance:Any,name:String,vararg args:Any?)=runCatching{call(instance,name,*args)}.getOrNull()
    fun callFirst(instance:Any,names:List<String>,vararg args:Any?):Boolean{for(name in names){val m=instance.javaClass.methods.firstOrNull{it.name==name&&it.parameterTypes.size==args.size}?:instance.javaClass.declaredMethods.firstOrNull{it.name==name&&it.parameterTypes.size==args.size};if(m!=null)return runCatching{m.isAccessible=true;m.invoke(instance,*args);true}.getOrElse{BridgeLog.e("callback $name failed",it);false}};return false}
    fun set(instance:Any,property:String,value:Any?){val setter="set"+property.replaceFirstChar{it.uppercase()};val m=allMethods(instance.javaClass).firstOrNull{it.name==setter&&it.parameterTypes.size==1};if(m!=null)runCatching{m.isAccessible=true;m.invoke(instance,value)}.onFailure{setField(instance,property,value)}else setField(instance,property,value)}
    fun fieldByType(instance:Any,className:String):Any?{var c:Class<*>?=instance.javaClass;while(c!=null){for(f in c.declaredFields)if(f.type.name==className)return runCatching{f.isAccessible=true;f.get(instance)}.getOrNull();c=c.superclass};return null}
    fun fields(instance:Any):Sequence<Pair<Field,Any?>> = sequence{var c:Class<*>?=instance.javaClass;while(c!=null){for(f in c.declaredFields){val v=runCatching{f.isAccessible=true;f.get(instance)}.getOrNull();yield(f to v)};c=c.superclass}}
    fun allMethods(clazz:Class<*>):List<Method>{val r=mutableListOf<Method>();var c:Class<*>?=clazz;while(c!=null){r+=c.declaredMethods;c=c.superclass};return r}
    private fun setField(instance:Any,property:String,value:Any?){var c:Class<*>?=instance.javaClass;while(c!=null){val f=c.declaredFields.firstOrNull{it.name==property};if(f!=null){f.isAccessible=true;f.set(instance,value);return};c=c.superclass};error("No field/setter for ${instance.javaClass.name}.$property")}
}
