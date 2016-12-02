package com.roysolberg.java.hdlserver.hdl.component;

public class FloorHeatingComponent extends HdlComponent {

    protected int numOfChannels;

    public FloorHeatingComponent(int subnet, int deviceId, int deviceType, String remark, int numOfChannels) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfChannels = numOfChannels;
    }

    @Override
    public String getDescription() {
        return "Floor Heating Module";
    }

    @Override
    public String getDeviceGroup() {
        return "hvac";
    }

    @Override
    public int getDefaultSortOrder() {
        return 480;
    }

    public int getNumOfChannels() {
        return numOfChannels;
    }

}
