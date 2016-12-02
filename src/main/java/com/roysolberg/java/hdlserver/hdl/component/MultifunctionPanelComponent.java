package com.roysolberg.java.hdlserver.hdl.component;

public class MultifunctionPanelComponent extends HdlComponent {

    public static final int KEY_MODE_INVALID = 0;
    public static final int KEY_MODE_SINGLE_ON_OFF = 1;
    public static final int KEY_MODE_SINGLE_ON = 2;
    public static final int KEY_MODE_SINGLE_OFF = 3;
    public static final int KEY_MODE_COMBINATION_ON = 4;
    public static final int KEY_MODE_COMBINATION_OFF = 5;
    public static final int KEY_MODE_MOMENTARY = 6;
    public static final int KEY_MODE_COMBINATION_ON_OFF = 7;

    protected final int rows, columns;
    protected final boolean hasTemperatureSensor;

    public MultifunctionPanelComponent(int subnet, int deviceId, int deviceType, String remark, int rows, int columns) {
        this(subnet, deviceId, deviceType, remark, rows, columns, true); // TODO: Do a check of this component and if there are any of them that have temp sensor
    }

    public MultifunctionPanelComponent(int subnet, int deviceId, int deviceType, String remark, int rows, int columns, boolean hasTemperatureSensor) {
        super(subnet, deviceId, deviceType, remark);
        this.rows = rows;
        this.columns = columns;
        this.hasTemperatureSensor = hasTemperatureSensor;
    }

    @Override
    public String getDescription() {
        return getNumOfButtons() + (getNumOfButtons() == 1 ? " key" : " keys") + " multifunction panel";
    }

    @Override
    public String getDeviceGroup() {
        return "panel";
    }

    @Override
    public int getDefaultSortOrder() {
        return 100;
    }

    public int getNumOfButtons() {
        return rows * columns;
    }

    public int getNumOfColumns() {
        return columns;
    }

    public boolean hasTemperatureSensor() {
        return hasTemperatureSensor;
    }

}
