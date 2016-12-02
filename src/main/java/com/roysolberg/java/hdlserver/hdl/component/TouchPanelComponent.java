package com.roysolberg.java.hdlserver.hdl.component;

public class TouchPanelComponent extends HdlComponent {

    public static final int KEY_MODE_INVALID = 0;
    public static final int KEY_MODE_SINGLE_ON_OFF = 1;
    public static final int KEY_MODE_SINGLE_ON = 2;
    public static final int KEY_MODE_SINGLE_OFF = 3;
    public static final int KEY_MODE_COMBINATION_ON = 4;
    public static final int KEY_MODE_COMBINATION_OFF = 5;
    public static final int KEY_MODE_MOMENTARY = 6;
    public static final int KEY_MODE_COMBINATION_ON_OFF = 7;

    protected final int numOfButtons;

    public TouchPanelComponent(int subnet, int deviceId, int deviceType, String remark, int numOfButtons) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfButtons = numOfButtons;
    }

    @Override
    public String getDescription() {
        return numOfButtons + (numOfButtons == 1 ? " key" : " keys") + " multifunction touch panel";
    }

    @Override
    public String getDeviceGroup() {
        return "panel";
    }

    @Override
    public int getDefaultSortOrder() {
        return 110;
    }

    public int getNumOfButtons() {
        return numOfButtons;
    }

}
