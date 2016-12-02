package com.roysolberg.java.hdlserver.hdl.component;

public class TemperatureSensorComponent extends HdlComponent {

    protected int numOfSensors;

    public TemperatureSensorComponent(int subnet, int deviceId, int deviceType, String remark, int numOfSensors) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfSensors = numOfSensors;
    }

    @Override
    public String getDescription() {
        return "Temperature Sensor";
    }

    @Override
    public String getDeviceGroup() {
        return "sensor";
    }

    @Override
    public int getDefaultSortOrder() {
        return 450;
    }

    public int getNumOfSensors() {
        return numOfSensors;
    }

}
