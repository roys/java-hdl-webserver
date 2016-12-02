package com.roysolberg.java.hdlserver.hdl.component;

public class DlpV3Component extends DlpV2Component {

    public DlpV3Component(int subnet, int deviceId, int deviceType, String remark, int numOfButtons, boolean hasImages, int numOfPages, boolean hasHeating, boolean hasCooling, boolean hasMusic) {
        super(subnet, deviceId, deviceType, remark, numOfButtons, hasImages, numOfPages, hasHeating, hasCooling, hasMusic);
    }

}
