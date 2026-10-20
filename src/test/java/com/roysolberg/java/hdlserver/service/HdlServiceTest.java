package com.roysolberg.java.hdlserver.service;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.DimmerComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.hdl.component.UnsupportedComponent;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The expected frames were worked out separately from the protocol description, not by this code.
 */
class HdlServiceTest {

    private static final InetAddress LOCAL_ADDRESS = address(192, 168, 1, 10);

    @Test
    void crcIsXmodem() {
        byte[] bytes = "123456789".getBytes(StandardCharsets.US_ASCII);

        assertEquals(0x31C3, HdlService.getCrc(bytes, 0, bytes.length)); // The standard check value
    }

    @Test
    void dimmerPackage() {
        Action.Command command = new Action.Command(1, 40, 0x0031, 3, null, null);

        byte[] bytes = HdlService.getDimmerPackage(LOCAL_ADDRESS, command, 30);

        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 10 0b fd ff fd 00 31 01 28 03 1e 00 00 0f 14 71", bytes);
    }

    @Test
    void commandPackage() {
        Action.Command command = new Action.Command(1, 81, 0xE3D8, 18, 1, null);

        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, command);

        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 0d 0b fd ff fd e3 d8 01 51 12 01 d1 ee", bytes);
    }

    @Test
    void packagesAreValid() {
        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, new Action.Command(1, 81, 0xE3D8, 18, 1, null));

        assertTrue(HdlService.isPackageValid(bytes));
        bytes[6] = 'X';
        assertFalse(HdlService.isPackageValid(bytes));
        assertFalse(HdlService.isPackageValid(new byte[22]));
        assertFalse(HdlService.isPackageValid(null));
    }

    @Test
    void componentFromSearchResponse() {
        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, new Action.Command(0, 0, 0x000F, null, null, null));
        bytes[17] = 1; // Subnet
        bytes[18] = 40; // Device
        bytes[19] = 0x02; // Type 600, a 6 channel dimmer
        bytes[20] = (byte) 0x58;
        byte[] remark = "Kitchen".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(remark, 0, bytes, 25, remark.length);

        HdlComponent component = HdlService.getComponentFromBytes(bytes, true, "ISO-8859-1");

        assertTrue(component instanceof DimmerComponent);
        assertEquals(1, component.getSubnet());
        assertEquals(40, component.getDeviceId());
        assertEquals(600, component.getDeviceType());
        assertEquals("Kitchen", component.getRemark());
        assertTrue(HdlService.isOperationCode(bytes, 0x000F));
    }

    @Test
    void ownAndSetupToolPackagesAreUnsupportedComponents() {
        assertTrue(HdlService.getComponent(11, 253, 0xFFFD, null) instanceof UnsupportedComponent);
        assertTrue(HdlService.getComponent(12, 254, 0xFFFE, null) instanceof UnsupportedComponent);
    }

    @Test
    void emptyRemarkIsNull() {
        byte[] bytes = new byte[50];
        Arrays.fill(bytes, 25, 45, (byte) 0xFF);

        assertNull(HdlService.getCleanRemark(bytes, 25, 20, null));
    }

    private static void assertFrame(String expectedHex, byte[] bytes) {
        int frameLength = 16 + (bytes[16] & 0xFF);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < frameLength; i++) {
            hex.append(i == 0 ? "" : " ").append(String.format("%02x", bytes[i] & 0xFF));
        }
        assertEquals(expectedHex, hex.toString());
    }

    private static InetAddress address(int... parts) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) parts[0], (byte) parts[1], (byte) parts[2], (byte) parts[3]});
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
