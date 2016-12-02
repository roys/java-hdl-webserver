package com.roysolberg.java.hdlserver.util;

/**
 * Created by roy on 29.11.2016.
 */
public class NetworkUtil {

    public static String getNicelyFormattedBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean firstByte = true;
        for (byte b : bytes) {
            if (!firstByte) {
                sb.append(" ");
            }
            firstByte = false;
            //sb.append("0x");
            sb.append(String.format("%02X", b & 0xff));
        }
        sb.append("]");
        return sb.toString();
    }

}
