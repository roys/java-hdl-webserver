package com.roysolberg.java.hdlserver.hdl.component;

public class HdlComponentFactory {

    // TODO: Isn't there a more sexy way to do this?
    public static int[] COMPONENTS_WITH_TEMPERATURE_SUPPORT = new int[]{134, 149, 156, 160, 162, 167, 165, 168, 172, 175, 224, 225, 226, 2023, 2032,
            292, 278, 294, 279, 281, 295, 297, 308, 321, 314, 322, 312, 323, 324, 327, 328, 329, 2024, 2029, 2030, 2031, 2061};
    public static int[] COMPONENTS_WITH_BUTTONS = new int[]{149, 156, 160, 162, 165, 167, 168, 172, 175, 224, 225, 226, 276, 277, 278, 279, 281, 292, 294, 295, 297, 298, 299, 2001, 2002, 2004, 2010, 2011, 2012, 2013, 2024, 2029, 2030, 2031, 2032, 2061};

    public static HdlComponent getHdlComponent(int subnetId, int deviceId, int deviceType, String remark) {
        switch (deviceType) {
            case 32: // 0x00A2
                return new DmxComponent(subnetId, deviceId, deviceType, remark);
            case 106: // 0x006A
            case 112: // 0x0070
            case 732: // 0x02DC
                return new HvacComponent(subnetId, deviceId, deviceType, remark);
            case 132: // 0x0084
                return new WideFieldPirSensorComponent(subnetId, deviceId, deviceType, remark);
            case 134: // 0x0086
                return new TemperatureSensorComponent(subnetId, deviceId, deviceType, remark, 4);
            case 149:
            case 156:
            case 160: // 0x00A0
                return new DlpComponent(subnetId, deviceId, deviceType, remark, 4, false, 4, true, true, true);
            case 162: // 0x00A2
            case 167: // 0x00A7
                return new DlpV2Component(subnetId, deviceId, deviceType, remark, 8, true, 4, true, true, true);
            case 165: // 0x00A5
            case 168: // 0x00A8
            case 172: // 0x00AC
            case 0x00AF: // 175
                return new DlpV3Component(subnetId, deviceId, deviceType, remark, 8, true, 4, true, true, true);
            case 211: // 0x00D3
                return new FloorHeatingComponent(subnetId, deviceId, deviceType, remark, 6);
            case 226: // 0x00E2
            case 2023: // 0x07E7
            case 2032: // 0x07F0
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 4);
            case 234: // 0x00EA
            case 235: // 0x00EB
                return new IpComponent(subnetId, deviceId, deviceType, remark);
            case 276: // 0x0114
            case 277: // 0x0115
            case 292:
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 1);
            case 225: // 0x00E1
            case 278:
            case 294: // 0x0126
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 3, 1);
            case 279: // 0x0117
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2);
            case 281: // 0x0119
            case 297: // 0x0129
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 6, 1);
            case 295: // 0x0127
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 1);
            case 298: // 0x012A
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2, false);
            case 299: // 0x012B
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 2, false);
            case 319: // 0x013F
                return new IrEmitterComponent(subnetId, deviceId, deviceType, remark, 249 /* 249 in Setup Tool, some say 255 and 200 */);
            case 0x0134:
            case 321: // 0x0141
                return new TwelveInOneSensorComponent(subnetId, deviceId, deviceType, remark);
            case 314: // 0x013A
            case 322: // 0x0142
            case 329: // 0x0149
                return new EightInOneSensorComponent(subnetId, deviceId, deviceType, remark);
            case 312: // 0x0138
            case 323: // 0x0143
            case 324: // 0x0144
            case 327: // 0x0147
            case 328: // 0x0148
                return new PassiveInfraredReceiverAndLuxDetectorComponent(subnetId, deviceId, deviceType, remark);
            case 121: // 0x0079
            case 137: // 0x0089
            case 354: // 0x0162
            case 355: // 0x0163
            case 356: // 0x0164
            case 358: // 0x0166
                return new DryContactInputComponent(subnetId, deviceId, deviceType, remark);
            case 424: // 0x01A8
            case 435: // 0x01B3
            case 438: // 0x01B6
            case 444: // 0x01BC
            case 447: // 0x01BF
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 4);
            case 428: // 0x01AC
            case 445: // 0x01BD
            case 436: // 0x01B4
            case 439: // 0x01B7
            case 448: // 0x01C0
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 8);
            case 451: // 0x01C3
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 16);
            case 430: // 0x01AE
            case 440: // 0x01B8
            case 446: // 0x01BE
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 12);
            case 600: // 0x0258
            case 608: // 0x0260
            case 617: // 0x0269
            case 621: // 0x026D
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 6);
            case 601: // 0x0259
            case 607: // 0x025F
            case 615: // 0x0267
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 4);
            case 705: // 0x02C1
            case 706: // 0x02C2
                return new CurtainControllerComponent(subnetId, deviceId, deviceType, remark);
            case 1018: // 0x03FA
                return new Rs232IpGatewayComponent(subnetId, deviceId, deviceType, remark);
            case 1106: // 0x0452
            case 1109: // 0x0455
                return new LogicComponent(subnetId, deviceId, deviceType, remark);
            case 2001:
            case 2010:
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 1, 2, true);
            case 2002:
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2, true);
            case 2004:
            case 2013: // 0x07DD
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 2, true);
            case 2011:
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2);
            case 2012: // 0x07DC
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 3, 2);
            case 2029: // 0x07ED
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 1);
            case 0x00E0:
            case 2030: // 0x07EE
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 2);
            case 2031: // 0x07EF
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 3);
            case 2024: // 0x07E8
            case 2061: // 0x080D
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 6);
            case 4000: // 0x0FA0
                return new SmsComponent(subnetId, deviceId, deviceType, remark);
            default:
                return new UnknownComponent(subnetId, deviceId, deviceType, remark);
        }
    }

}
