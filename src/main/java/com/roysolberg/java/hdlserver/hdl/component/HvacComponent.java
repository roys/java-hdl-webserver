package com.roysolberg.java.hdlserver.hdl.component;

public class HvacComponent extends HdlComponent {

    private static final long serialVersionUID = -148380709854203521L;

    public HvacComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "HVAC";
    }

    @Override
    public String getDeviceGroup() {
        return "hvac";
    }

    @Override
    public int getDefaultSortOrder() {
        return 805;
    }

}
