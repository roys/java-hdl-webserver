package com.roysolberg.java.hdlserver.hdl.component;

public class DmxComponent extends HdlComponent {

    private static final long serialVersionUID = -8533046715631801948L;

    public DmxComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "48 channels scene controller bus";
    }

    @Override
    public String getDeviceGroup() {
        return "dmx";
    }

    @Override
    public int getDefaultSortOrder() {
        return 990;
    }

}
