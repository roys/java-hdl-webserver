package com.roysolberg.java.hdlserver.hdl.component;

public class EightInOneSensorComponent extends HdlComponent {

    private static final long serialVersionUID = 5504507296932505292L;

    public EightInOneSensorComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "8 in 1 Multifunction Sensor";
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
