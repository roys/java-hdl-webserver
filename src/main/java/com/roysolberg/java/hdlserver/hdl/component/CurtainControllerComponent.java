package com.roysolberg.java.hdlserver.hdl.component;

public class CurtainControllerComponent extends HdlComponent {

    public CurtainControllerComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Curtain controller";
    }

    @Override
    public String getDeviceGroup() {
        return "controller";
    }

    @Override
    public int getDefaultSortOrder() {
        return 770;
    }

}
