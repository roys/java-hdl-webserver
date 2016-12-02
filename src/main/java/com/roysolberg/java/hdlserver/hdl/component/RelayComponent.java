package com.roysolberg.java.hdlserver.hdl.component;

public class RelayComponent extends HdlComponent {

    protected int numOfChannels;

    public RelayComponent(int subnet, int deviceId, int deviceType, String remark, int numOfChannels) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfChannels = numOfChannels;
    }

    @Override
    public String getDescription() {
        return "Relay";
    }

    @Override
    public String getDeviceGroup() {
        return "relay";
    }

    @Override
    public int getDefaultSortOrder() {
        return 510;
    }

    public int getNumOfChannels() {
        return numOfChannels;
    }

}
