package com.roysolberg.java.hdlserver.hdl.component;

public class DlpV2Component extends DlpComponent {

    public DlpV2Component(int subnet, int deviceId, int deviceType, String remark, int numOfButtons, boolean hasImages, int numOfPages, boolean hasHeating, boolean hasCooling, boolean hasMusic) {
        super(subnet, deviceId, deviceType, remark, numOfButtons, hasImages, numOfPages, hasHeating, hasCooling, hasMusic);
    }

}
