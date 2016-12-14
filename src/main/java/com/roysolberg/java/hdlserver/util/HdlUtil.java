package com.roysolberg.java.hdlserver.util;

import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;

/**
 * Utilities for the HDL-BUS Pro protocol.
 */
public class HdlUtil {

    private static Logger logger = LogManager.getLogger(HdlUtil.class);

    public final static int BYTE_POSITION_LENGTH = 16;
    public final static int BYTE_POSITION_SUBNET_ID = 17;
    public final static int BYTE_POSITION_DEVICE_ID = 18;
    public final static int BYTE_POSITION_DEVICE_TYPE_BYTE_1 = 19;
    public final static int BYTE_POSITION_DEVICE_TYPE_BYTE_2 = 20;
    public final static int BYTE_POSITION_OPERATION_CODE_BYTE_1 = 21;
    public final static int BYTE_POSITION_OPERATION_CODE_BYTE_2 = 22;
    public final static int BYTE_POSITION_TARGET_SUBNET_ID = 23;
    public final static int BYTE_POSITION_TARGET_DEVICE_ID = 24;
    public final static int BYTE_POSITION_CONTENT = 25;
    protected final static int BYTE_POSITION_REMARK = 25;
    protected final static byte OPERATE_CODE_SEARCH_BYTE_1 = (byte) 0x00;
    protected final static byte OPERATE_CODE_SEARCH_BYTE_2 = (byte) 0x0E;
    protected final static byte OPERATE_CODE_SEARCH_RESPONSE_BYTE_1 = (byte) 0x00;
    protected final static byte OPERATE_CODE_SEARCH_RESPONSE_BYTE_2 = (byte) 0x0F;
    protected final static byte OPERATE_CODE_CONTROL_SWITCH_BYTE_1 = (byte) 0xE0;
    protected final static byte OPERATE_CODE_CONTROL_SWITCH_BYTE_2 = (byte) 0x1C;
    protected static final byte TARGET_SUBNET_ID_ALL = (byte) 0xFF;
    protected static final byte TARGET_DEVICE_ID_ALL = (byte) 0xFF;

    public static boolean isPackageValid(byte[] bytes) {
        if (bytes != null && bytes.length >= 23) {
            byte[] fixedBytes = new byte[]{'H', 'D', 'L', 'M', 'I', 'R', 'A', 'C', 'L', 'E', (byte) 0xAA, (byte) 0xAA}; // TODO: should be constant + used for default package creation + fix those 0xaa
            for (int i = 0; i < fixedBytes.length; i++) {
                if (bytes[i + 4] != fixedBytes[i]) {
                    return false;
                }
                // TODO: Verify CRC
                return true;
            }
        }
        return false;
    }

    public static String getNiceOperationCode(int operationCode) {
        switch (operationCode) {
            case 14: // 0x000E
                return "Search";
            case 15: // 0x000F
                return "Response search";
            case 49: // 0x0031
                return "Single channel control";
            case 50: // 0x0032
                return "Response single channel control";
            case 51: // 0x0033
                return "Read status of channels";
            case 52: // 0x0034
                return "Response read status of channels";
            case 0x15D0:
                return "Dry contact";
            case 0x1604:
            case 0x1645:
                return "Read sensor status";
            case 0x1605:
            case 0x1646:
                return "Response read sensor status";
            case 0x1647:
                return "Broadcast sensor status";
            case 0x1944:
                return "Read floor heating status from DLP";
            case 0x1945:
                return "Response read floor heating status from DLP";
            case 0x1C5C:
                return "Control floor heating status";
            case 0x1C5D:
                return "Response control floor heating status";
            case 0x1C5E:
                return "Read status from floor heating module";
            case 0x1C5F:
                return "Response read status from floor heating module";
            case 0x1D02:
                return "Read channel remark";
            case 0x1D03:
                return "Response read channel remark";
            case 0xC100:
                return "Read message";
            case 0xC101:
                return "Response read message";
            case 0xC102:
                return "Read signal strength";
            case 0xC103:
                return "Response read signal strength";
            case 0xE017:
                return "Broadcast status of UV switches";
            case 0xE01C:
                return "UV switch control";
            case 0xE01D:
                return "Response UV switch control";
            case 0xF00E:
                return "Read channel remark";
            case 0xF00F:
                return "Response read channel remark";
            case 6476: // 0x194C
                return "Read image";
            case 6472: // 0x1948
                return "Read temperature";
            case 6473: // 0x1949
                return "Response read temperature";
            case 6477: // 0x194D
                return "Response read image";
            case 6506: // 0x196A
                return "Read key merge";
            case 6507: // 0x196B
                return "Response read key merge";
            case 0x198C:
                return "Read button colors";
            case 0x198D:
                return "Response read button colors";
            case 7168: // 0x1C00
                return "Read temperature";
            case 7169: // 0x1C01
                return "Response read temperature";
            case 12295: // 0x3007
                return "Read login";
            case 12296: // 0x3008
                return "Response read login";
            case 12297: // 0x3009
                return "Read connection addresses";
            case 12298: // 0x300A
                return "Response read connection addresses";
            case 0xC04E:
                return "Read SMS service center";
            case 0xC04F:
                return "Response read SMS service center";
            case 0xC072:
                return "Read country code";
            case 0xC073:
                return "Response read country code";
            case 0xC090:
                return "Read IMEI";
            case 0xC091:
                return "Response read IMEI";
            case 57644: // 0xE12C
                return "Read page status";
            case 57645: // 0xE12D
                return "Response read page status";
            case 0xE3D4:
                return "Send SMS";
            case 0xE3D5:
                return "Response send SMS";
            case 58328: // 0xE3D8
                return "Panel control";
            case 58329: // 0xE3D9
                return "Response panel control";
            case 58343: // 0xE3E7
                return "Read temperature";
            case 58344: // 0xE3E8
                return "Response read temperature";
            case 0xE008:
                return "Read key mode";
            case 0xE009:
                return "Response read key mode";
            case 0xE150:
                return "Read touch panel status";
            case 0xE151:
                return "Response read touch panel status";
            case 0xE14C:
                return "Read touch panel colours";
            case 0xE14D:
                return "Response read touch panel colours";
            case 0xE3DA:
                return "Read panel status";
            case 0xE3DB:
                return "Response read panel status";
            case 55808: // 0xDA00
                return "Read system date and time";
            case 55809: // 0xDA01
                return "Response read system date and time";
            case 55876: // 0xDA44
                return "Broadcast system date and time (every minute)";
            case 58341: // 0xE3E5
                return "Broadcast temperature";
            case 61439: // 0xEFFF
                return "Broadcast status of scene";
            case 61494: // 0xF036
                return "Broadcast status of sequence";
            case 0xF037:
                return "Read IP module config";
            case 0xF038:
                return "Response read IP module config";
            default:
                return "Unknown operation";
        }
    }

    public static String getNiceOperationCode(byte[] bytes) {
        int operationCode = (bytes[BYTE_POSITION_OPERATION_CODE_BYTE_2] & 0xFF) | (bytes[BYTE_POSITION_OPERATION_CODE_BYTE_1] & 0xFF) << 8;
        return getNiceOperationCode(operationCode);
    }

    public static String getNicelyDescribedBytes(byte[] bytes, HdlComponent hdlComponent) {
        StringBuilder sb = new StringBuilder();
        // From
        sb.append("From: ");
        sb.append(bytes[0] & 0xFF);
        sb.append(".");
        sb.append(bytes[1] & 0xFF);
        sb.append(".");
        sb.append(bytes[2] & 0xFF);
        sb.append(".");
        sb.append(bytes[3] & 0xFF);
        sb.append("/");
        sb.append(hdlComponent.getSubnet());
        sb.append("/");
        sb.append(hdlComponent.getDeviceId());
        sb.append(", type 0x");
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_DEVICE_TYPE_BYTE_1] & 0xFF));
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_DEVICE_TYPE_BYTE_2] & 0xFF));
        sb.append(" (");
        // Operation
        sb.append(hdlComponent.getDescription());
        sb.append("), operation: 0x");
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_OPERATION_CODE_BYTE_1] & 0xFF));
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_OPERATION_CODE_BYTE_2] & 0xFF));
        sb.append(" (");
        sb.append(HdlUtil.getNiceOperationCode(bytes));
        sb.append("), ");
        // Content
        int contentLength = bytes[HdlUtil.BYTE_POSITION_LENGTH] & 0xFF;
        sb.append("content length: ");
        sb.append(contentLength);
        sb.append(", ");
        if (contentLength > 11) { // Got content
            contentLength = Math.min(contentLength, 78);
            sb.append("content: ");
            sb.append(NetworkUtil.getNicelyFormattedBytes(Arrays.copyOfRange(bytes, HdlUtil.BYTE_POSITION_CONTENT, HdlUtil.BYTE_POSITION_CONTENT + contentLength - 11)));
            sb.append(", ");
        }
        // CRC
        sb.append("crc: 0x");
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_LENGTH + contentLength] & 0xFF));
        sb.append(String.format("%02X", bytes[HdlUtil.BYTE_POSITION_LENGTH + contentLength + 1] & 0xFF));
        sb.append(", ");
        // Target
        int targetDeviceId = bytes[HdlUtil.BYTE_POSITION_TARGET_DEVICE_ID] & 0xFF;
        sb.append("target: ");
        sb.append(bytes[HdlUtil.BYTE_POSITION_TARGET_SUBNET_ID] & 0xFF);
        sb.append("/");
        sb.append(targetDeviceId);
        return sb.toString();
    }

    public static String getComponentId(HdlComponent hdlComponent) {
        return hdlComponent.getSubnet() + "-" + hdlComponent.getDeviceId() + "-" + hdlComponent.getDeviceType();
    }

}
