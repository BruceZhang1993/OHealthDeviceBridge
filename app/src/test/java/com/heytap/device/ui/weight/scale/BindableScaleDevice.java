package com.heytap.device.ui.weight.scale;
public class BindableScaleDevice {
 public final boolean isConnectScale;public final Object rawRef;
 public BindableScaleDevice(ScaleVendor v,String mac,String name,String model,boolean connect,Object raw){isConnectScale=connect;rawRef=raw;}
}
