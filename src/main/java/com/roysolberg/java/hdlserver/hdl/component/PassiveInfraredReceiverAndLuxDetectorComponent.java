package com.roysolberg.java.hdlserver.hdl.component;

public class PassiveInfraredReceiverAndLuxDetectorComponent extends HdlComponent {

    public PassiveInfraredReceiverAndLuxDetectorComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Ceiling Mount PIR Sensor";
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
