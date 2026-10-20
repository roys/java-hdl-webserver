package com.roysolberg.java.hdlserver.hdl.component;

public class WideFieldPirSensorComponent extends HdlComponent {

    private static final long serialVersionUID = 8184323527475870738L;

    public WideFieldPirSensorComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Wide Field PIR Sensor";
    }

    @Override
    public String getDeviceGroup() {
        return "sensor";
    }

    @Override
    public int getDefaultSortOrder() {
        return 801;
    }

}
