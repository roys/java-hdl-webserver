package com.roysolberg.java.hdlserver.hdl.component;

public class Rs232IpGatewayComponent extends HdlComponent {

    private static final long serialVersionUID = -961768629124809759L;

    public Rs232IpGatewayComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "RS 232 Ip Gateway";
    }

    @Override
    public String getDeviceGroup() {
        return "ip";
    }

    @Override
    public int getDefaultSortOrder() {
        return 1010;
    }

}
