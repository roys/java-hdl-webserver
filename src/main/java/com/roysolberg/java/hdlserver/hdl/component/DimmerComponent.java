package com.roysolberg.java.hdlserver.hdl.component;

public class DimmerComponent extends RelayComponent {

    private static final long serialVersionUID = 7427022756593443576L;

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
