package com.roysolberg.java.hdlserver.hdl.component;

public class UnknownComponent extends HdlComponent {

    private static final long serialVersionUID = -3073046441885501644L;

    public UnknownComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Unknown device";
    }

    @Override
    public String getDeviceGroup() {
        return "unknown";
    }

    @Override
    public int getDefaultSortOrder() {
        return 1100;
    }

}
