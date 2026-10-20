package com.roysolberg.java.hdlserver.hdl.component;

public class IpComponent extends HdlComponent {

    private static final long serialVersionUID = -6338398453571187617L;

    public IpComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "1 port switchboard";
    }

    @Override
    public String getDeviceGroup() {
        return "ip";
    }

    @Override
    public int getDefaultSortOrder() {
        return 1000;
    }

}
