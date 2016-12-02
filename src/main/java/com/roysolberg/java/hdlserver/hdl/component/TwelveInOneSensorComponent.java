package com.roysolberg.java.hdlserver.hdl.component;

public class TwelveInOneSensorComponent extends HdlComponent {

    public TwelveInOneSensorComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "12 in 1 Multifunction Sensor";
    }

    @Override
    public String getDeviceGroup() {
        return "sensor";
    }

    @Override
    public int getDefaultSortOrder() {
        return 800;
    }

}
