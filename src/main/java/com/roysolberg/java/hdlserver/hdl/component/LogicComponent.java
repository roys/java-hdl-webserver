package com.roysolberg.java.hdlserver.hdl.component;

public class LogicComponent extends HdlComponent {

    public LogicComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "Logic Module";
    } // TOOD: What does the setup tool call this one?

    @Override
    public String getDeviceGroup() {
        return "logic";
    }

    @Override
    public int getDefaultSortOrder() {
        return 750;
    }

}
