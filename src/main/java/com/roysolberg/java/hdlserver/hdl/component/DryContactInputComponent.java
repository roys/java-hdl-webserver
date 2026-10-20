package com.roysolberg.java.hdlserver.hdl.component;

public class DryContactInputComponent extends HdlComponent {

    private static final long serialVersionUID = 5324465015905576117L;

    public DryContactInputComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Sensor Input Module";
    }

    @Override
    public String getDeviceGroup() {
        return "input";
    }

    @Override
    public int getDefaultSortOrder() {
        return 810;
    }

}
