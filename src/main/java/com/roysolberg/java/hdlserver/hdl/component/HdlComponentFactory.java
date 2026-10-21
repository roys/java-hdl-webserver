package com.roysolberg.java.hdlserver.hdl.component;

public class HdlComponentFactory {

    // TODO: Isn't there a more sexy way to do this?
    public static int[] COMPONENTS_WITH_TEMPERATURE_SUPPORT = new int[]{134, 149, 156, 160, 162, 167, 165, 168, 172, 175, 224, 225, 226, 2023, 2032,
            292, 278, 294, 279, 281, 295, 297, 308, 321, 314, 322, 312, 323, 324, 327, 328, 329, 2024, 2029, 2030, 2031, 2061};
    public static int[] COMPONENTS_WITH_BUTTONS = new int[]{149, 156, 160, 162, 165, 167, 168, 172, 175, 224, 225, 226, 276, 277, 278, 279, 281, 292, 294, 295, 297, 298, 299, 2001, 2002, 2004, 2010, 2011, 2012, 2013, 2024, 2029, 2030, 2031, 2032, 2061};

    public static HdlComponent getHdlComponent(int subnetId, int deviceId, int deviceType, String remark) {
        switch (deviceType) {
            case 17: // 0x0011
            case 36: // 0x0024
            case 600: // 0x0258
            case 608: // 0x0260
            case 613: // 0x0265
            case 617: // 0x0269
            case 621: // 0x026D
            case 622: // 0x026E
            case 638: // 0x027E
            case 644: // 0x0284
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 6);
            case 32: // 0x0020
                return new DmxComponent(subnetId, deviceId, deviceType, remark);
            case 33: // 0x0021
            case 38: // 0x0026
            case 4000: // 0x0FA0
                return new SmsComponent(subnetId, deviceId, deviceType, remark);
            case 42: // 0x002A
            case 4301: // 0x10CD
            case 4306: // 0x10D2
            case 4307: // 0x10D3
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "DALI controller");
            case 86: // 0x0056
            case 758: // 0x02F6
            case 1303: // 0x0517
            case 2182: // 0x0886
            case 2192: // 0x0890
            case 4500: // 0x1194
            case 4599: // 0x11F7
            case 4700: // 0x125C
            case 4701: // 0x125D
            case 30001: // 0x7531
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Energy module");
            case 106: // 0x006A
            case 112: // 0x0070
            case 723: // 0x02D3
            case 732: // 0x02DC
            case 735: // 0x02DF
            case 3813: // 0x0EE5
                return new HvacComponent(subnetId, deviceId, deviceType, remark);
            case 116: // 0x0074
            case 119: // 0x0077
            case 121: // 0x0079
            case 135: // 0x0087
            case 137: // 0x0089
            case 354: // 0x0162
            case 355: // 0x0163
            case 356: // 0x0164
            case 358: // 0x0166
            case 363: // 0x016B
            case 365: // 0x016D
            case 367: // 0x016F
            case 371: // 0x0173
            case 388: // 0x0184
            case 393: // 0x0189
            case 394: // 0x018A
            case 5900: // 0x170C
                return new DryContactInputComponent(subnetId, deviceId, deviceType, remark);
            case 132: // 0x0084
                return new WideFieldPirSensorComponent(subnetId, deviceId, deviceType, remark);
            case 134: // 0x0086
                return new TemperatureSensorComponent(subnetId, deviceId, deviceType, remark, 4);
            case 149: // 0x0095
            case 156: // 0x009C
            case 160: // 0x00A0
                return new DlpComponent(subnetId, deviceId, deviceType, remark, 4, false, 4, true, true, true);
            case 158: // 0x009E
            case 162: // 0x00A2
            case 167: // 0x00A7
            case 180: // 0x00B4
            case 186: // 0x00BA
                return new DlpV2Component(subnetId, deviceId, deviceType, remark, 8, true, 4, true, true, true);
            case 165: // 0x00A5
            case 168: // 0x00A8
            case 172: // 0x00AC
            case 175: // 0x00AF
            case 188: // 0x00BC
            case 9100: // 0x238C
            case 9131: // 0x23AB
                return new DlpV3Component(subnetId, deviceId, deviceType, remark, 8, true, 4, true, true, true);
            case 185: // 0x00B9
            case 192: // 0x00C0
            case 9104: // 0x2390
            case 9107: // 0x2393
            case 9116: // 0x239C
            case 9149: // 0x23BD
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Touch panel with display");
            case 207: // 0x00CF
            case 211: // 0x00D3
            case 212: // 0x00D4
            case 213: // 0x00D5
                return new FloorHeatingComponent(subnetId, deviceId, deviceType, remark, 6);
            case 224: // 0x00E0
            case 2020: // 0x07E4
            case 2022: // 0x07E6
            case 2030: // 0x07EE
            case 2041: // 0x07F9
            case 2063: // 0x080F
            case 2068: // 0x0814
            case 2124: // 0x084C
            case 2128: // 0x0850
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 2);
            case 225: // 0x00E1
            case 278: // 0x0116
            case 294: // 0x0126
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 3, 1);
            case 226: // 0x00E2
            case 2021: // 0x07E5
            case 2023: // 0x07E7
            case 2032: // 0x07F0
            case 2043: // 0x07FB
            case 2065: // 0x0811
            case 2067: // 0x0813
            case 2107: // 0x083B
            case 2125: // 0x084D
            case 2130: // 0x0852
            case 2268: // 0x08DC
            case 5057: // 0x13C1
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 4);
            case 228: // 0x00E4
            case 231: // 0x00E7
            case 232: // 0x00E8
            case 234: // 0x00EA
            case 235: // 0x00EB
            case 4605: // 0x11FD
            case 4611: // 0x1203
            case 4615: // 0x1207
            case 4617: // 0x1209
            case 4619: // 0x120B
            case 9600: // 0x2580
                return new IpComponent(subnetId, deviceId, deviceType, remark);
            case 276: // 0x0114
            case 277: // 0x0115
            case 292: // 0x0124
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 1);
            case 279: // 0x0117
            case 2011: // 0x07DB
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2);
            case 281: // 0x0119
            case 297: // 0x0129
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 6, 1);
            case 288: // 0x0120
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 1, 1);
            case 295: // 0x0127
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 1);
            case 298: // 0x012A
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2, false);
            case 299: // 0x012B
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 2, false);
            case 305: // 0x0131
            case 309: // 0x0135
            case 314: // 0x013A
            case 318: // 0x013E
            case 322: // 0x0142
            case 329: // 0x0149
            case 375: // 0x0177
                return new EightInOneSensorComponent(subnetId, deviceId, deviceType, remark);
            case 308: // 0x0134
            case 321: // 0x0141
                return new TwelveInOneSensorComponent(subnetId, deviceId, deviceType, remark);
            case 310: // 0x0136
            case 311: // 0x0137
            case 312: // 0x0138
            case 317: // 0x013D
            case 323: // 0x0143
            case 324: // 0x0144
            case 326: // 0x0146
            case 327: // 0x0147
            case 328: // 0x0148
            case 330: // 0x014A
            case 335: // 0x014F
            case 336: // 0x0150
            case 341: // 0x0155
            case 342: // 0x0156
            case 344: // 0x0158
            case 1500: // 0x05DC
            case 1750: // 0x06D6
            case 10310: // 0x2846
            case 10311: // 0x2847
            case 10330: // 0x285A
                return new PassiveInfraredReceiverAndLuxDetectorComponent(subnetId, deviceId, deviceType, remark);
            case 319: // 0x013F
                return new IrEmitterComponent(subnetId, deviceId, deviceType, remark, 249);
            case 424: // 0x01A8
            case 435: // 0x01B3
            case 438: // 0x01B6
            case 444: // 0x01BC
            case 447: // 0x01BF
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 4);
            case 428: // 0x01AC
            case 436: // 0x01B4
            case 439: // 0x01B7
            case 445: // 0x01BD
            case 448: // 0x01C0
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 8);
            case 430: // 0x01AE
            case 440: // 0x01B8
            case 446: // 0x01BE
            case 449: // 0x01C1
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 12);
            case 450: // 0x01C2
            case 451: // 0x01C3
                return new RelayComponent(subnetId, deviceId, deviceType, remark, 16);
            case 601: // 0x0259
            case 607: // 0x025F
            case 615: // 0x0267
            case 620: // 0x026C
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 4);
            case 602: // 0x025A
            case 606: // 0x025E
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 2);
            case 705: // 0x02C1
            case 706: // 0x02C2
            case 713: // 0x02C9
            case 1250: // 0x04E2
                return new CurtainControllerComponent(subnetId, deviceId, deviceType, remark);
            case 738: // 0x02E2
            case 6500: // 0x1964
            case 16500: // 0x4074
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Fan controller");
            case 854: // 0x0356
            case 1600: // 0x0640
            case 1601: // 0x0641
                return new DimmerComponent(subnetId, deviceId, deviceType, remark, 48);
            case 894: // 0x037E
            case 895: // 0x037F
            case 896: // 0x0380
            case 897: // 0x0381
            case 898: // 0x0382
            case 899: // 0x0383
            case 900: // 0x0384
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Power meter");
            case 918: // 0x0396
            case 920: // 0x0398
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Music player");
            case 1018: // 0x03FA
            case 1027: // 0x0403
                return new Rs232IpGatewayComponent(subnetId, deviceId, deviceType, remark);
            case 1100: // 0x044C
            case 1101: // 0x044D
            case 1102: // 0x044E
            case 1103: // 0x044F
            case 1104: // 0x0450
            case 1105: // 0x0451
            case 1106: // 0x0452
            case 1107: // 0x0453
            case 1108: // 0x0454
            case 1109: // 0x0455
            case 1111: // 0x0457
            case 1134: // 0x046E
            case 11111: // 0x2B67
                return new LogicComponent(subnetId, deviceId, deviceType, remark);
            case 2001: // 0x07D1
            case 2010: // 0x07DA
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 1, 2, true);
            case 2002: // 0x07D2
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 2, 2, true);
            case 2003: // 0x07D3
            case 2012: // 0x07DC
            case 2046: // 0x07FE
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 3, 2);
            case 2004: // 0x07D4
            case 2013: // 0x07DD
                return new MultifunctionPanelComponent(subnetId, deviceId, deviceType, remark, 4, 2, true);
            case 2019: // 0x07E3
            case 2029: // 0x07ED
            case 2040: // 0x07F8
            case 2062: // 0x080E
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 1);
            case 2024: // 0x07E8
            case 2061: // 0x080D
            case 2091: // 0x082B
            case 2119: // 0x0847
            case 2126: // 0x084E
            case 2267: // 0x08DB
            case 5056: // 0x13C0
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 6);
            case 2031: // 0x07EF
            case 2042: // 0x07FA
                return new TouchPanelComponent(subnetId, deviceId, deviceType, remark, 3);
            case 3046: // 0x0BE6
            case 3047: // 0x0BE7
            case 13046: // 0x32F6
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Security module");
            case 3534: // 0x0DCE
            case 3543: // 0x0DD7
            case 3559: // 0x0DE7
            case 3560: // 0x0DE8
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Home control unit");
            default:
                return new UnknownComponent(subnetId, deviceId, deviceType, remark);
        }
    }

}
