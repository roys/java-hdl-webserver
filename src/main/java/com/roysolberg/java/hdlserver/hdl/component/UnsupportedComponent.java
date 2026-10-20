package com.roysolberg.java.hdlserver.hdl.component;

public class UnsupportedComponent extends HdlComponent {

    private static final long serialVersionUID = -2298784800549762956L;

    protected String description;

    public UnsupportedComponent(int subnet, int deviceId, int deviceType, String remark, String description) {
        super(subnet, deviceId, deviceType, remark);
        this.description = description;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getDeviceGroup() {
        return "unsupported";
    }

    @Override
    public int getDefaultSortOrder() {
        return 2000;
    }

}
