package com.roysolberg.java.hdlserver.hdl.component;

import java.io.Serializable;

public abstract class HdlComponent implements Serializable{

    protected int subnet; // 0-254
    protected int deviceId; // 0-254
    protected int deviceType; // 0-65535 // TODO: Why do we use both "device" and "component" for the same thing?
    protected String remark;

    public HdlComponent(int subnet, int deviceId, int deviceType, String remark) {
        this.subnet = subnet;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.remark = getCleanRemark(remark);
    }

    protected String getCleanRemark(String remark) {
        if (remark != null) {
            remark = remark.replaceAll("\u0000", "");
        }
        return remark;
    }

    public int getSubnet() {
        return subnet;
    }

    public void setSubnet(int subnet) {
        this.subnet = subnet;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public int getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(int deviceType) {
        this.deviceType = deviceType;
    }

    public abstract String getDeviceGroup();

    public String getRemark() {
        return remark;
    }

    public void setDescription(String remark) {
        this.remark = remark;
    }

    public abstract String getDescription();

    public abstract int getDefaultSortOrder();

    @Override
    public String toString() {
        return "HdlComponent[remark:" + remark + ",description:" + getDescription() + ",type:" + deviceType + ",subnet:" + subnet + ",device:" + deviceId + "]";
    }

}
