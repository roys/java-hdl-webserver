package com.roysolberg.java.hdlserver.hdl.component;

public class DimmerComponent extends RelayComponent {

    public DimmerComponent(int subnet, int deviceId, int deviceType, String remark, int numOfChannels) {
        super(subnet, deviceId, deviceType, remark, numOfChannels);
    }

    @Override
    public String getDescription() {
        return "Dimmer";
    }

    @Override
    public String getDeviceGroup() {
        return "dimmer";
    }

    @Override
    public int getDefaultSortOrder() {
        return 500;
    }

}
