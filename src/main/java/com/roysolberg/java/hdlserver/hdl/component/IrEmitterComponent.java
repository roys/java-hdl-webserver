package com.roysolberg.java.hdlserver.hdl.component;

// TODO: It would be nice to be able to read the IR codes command and let the user send them
public class IrEmitterComponent extends HdlComponent {

    protected int numOfIrCodes;

    public IrEmitterComponent(int subnet, int deviceId, int deviceType, String remark, int numOfIrCodes) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfIrCodes = numOfIrCodes;
    }

    @Override
    public String getDescription() {
        return "IR Emitter";
    }

    @Override
    public String getDeviceGroup() {
        return "emitter";
    }

    @Override
    public int getDefaultSortOrder() {
        return 802;
    }

}
