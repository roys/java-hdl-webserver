package com.roysolberg.java.hdlserver.service;

import com.roysolberg.java.hdlserver.HdlServer;
import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.DimmerComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

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

        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 10 0b fc ff fb 00 31 01 28 03 1e 00 00 0f 64 bb", bytes);
    }

    @Test
    void commandPackage() {
        Action.Command command = new Action.Command(1, 81, 0xE3D8, 18, 1, null);

        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, command);

        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 0d 0b fc ff fb e3 d8 01 51 12 01 9b e8", bytes);
    }

    @Test
    void singleChannelControlInACustomActionIsTheWholeCommand() {
        Action.Command command = new Action.Command(1, 40, 0x0031, 3, 30, 300);

        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, command);

        // Channel 3 to 30 % over 300 seconds (01 2c), like a dimmer action's
        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 10 0b fc ff fb 00 31 01 28 03 1e 01 2c 0f 10 00", bytes);
    }

    @Test
    void sceneControl() {
        Action.Command command = new Action.Command(1, 20, 0x0002, 1, 4, null);

        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, command);

        // Area 1, scene 4
        assertFrame("c0 a8 01 0a 48 44 4c 4d 49 52 41 43 4c 45 aa aa 0d 0b fc ff fb 00 02 01 14 01 04 5f a4", bytes);
    }

    @Test
    void aCommandStopsAtAParameterLeftOut() {
        // Like an action saved before parameters were checked
        Action.Command command = new Action.Command(1, 20, 0x0002, null, 4, null);

        byte[] bytes = HdlService.getCommandPackage(LOCAL_ADDRESS, command);

        assertEquals(11, bytes[HdlService.BYTE_POSITION_LENGTH], "Nothing shifted into the wrong place");
    }

    @Test
    void aDeviceAnsweringWithAnotherTypeReplacesWhatWasStored() {
        Map<String, HdlComponent> components = new HashMap<>();
        components.put("1-4-600", new DimmerComponent(1, 4, 600, "Not this one", 6));
        assertTrue(HdlService.storeFoundDevice(components, new DimmerComponent(1, 40, 600, "Kitchen", 6)));
        assertFalse(HdlService.storeFoundDevice(components, new DimmerComponent(1, 40, 600, "Kitchen", 6)), "Nothing new");

        assertTrue(HdlService.storeFoundDevice(components, new DimmerComponent(1, 40, 601, "Kitchen", 4)));

        assertEquals(new HashSet<>(Arrays.asList("1-4-600", "1-40-601")), components.keySet());
    }

    @Test
    void aReplacedDeviceIsStillReplacedAfterARestart(@TempDir Path folder) {
        String file = folder.resolve("hdlserver.db").toString();
        try (DB database = HdlServer.openDatabase(file)) {
            HdlService.storeFoundDevice(devices(database), new DimmerComponent(1, 40, 600, "Kitchen", 6));
            database.commit();
        }
        try (DB database = HdlServer.openDatabase(file)) {
            HdlService.storeFoundDevice(devices(database), new DimmerComponent(1, 40, 601, "Kitchen dimmer", 4));
            database.commit();
        }

        try (DB database = HdlServer.openDatabase(file)) {
            Map<String, HdlComponent> devices = devices(database);
            assertEquals(Collections.singleton("1-40-601"), devices.keySet());
            assertEquals("Kitchen dimmer", devices.get("1-40-601").getRemark());
            assertEquals(4, ((DimmerComponent) devices.get("1-40-601")).getNumOfChannels());
        }
    }

    @Test
    void aSearchIsNotStartedWhileOneIsGoingOn() {
        try (DB database = DBMaker.memoryDB().make()) {
            HdlService hdlService = new HdlService(database) {
                @Override
                public void doOneTimeQuickSearchForComponents() {
                    // Nothing to send in a test
                }
            };

            assertTrue(hdlService.searchForComponents());
            assertFalse(hdlService.searchForComponents());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HdlComponent> devices(DB database) {
        return (Map<String, HdlComponent>) (Map<?, ?>) database.hashMap("components").createOrOpen();
    }

    @Test
    void searchRepliesUpdateModulesThatHaveChanged() {
        HdlComponent stored = HdlService.getComponent(1, 0, 0x2580, null);

        assertTrue(HdlService.isNewOrChanged(null, stored));
        assertFalse(HdlService.isNewOrChanged(stored, HdlService.getComponent(1, 0, 0x2580, null)));
        assertTrue(HdlService.isNewOrChanged(stored, HdlService.getComponent(1, 0, 0x2580, "Gateway")), "Renamed");
        assertTrue(HdlService.isNewOrChanged(HdlService.getComponent(1, 0, 0x9999, null), stored), "Type now known");
        assertEquals("IP module", stored.getDescription());
    }

    @Test
    void moreDeviceTypesAreKnown() {
        assertEquals("Ceiling Mount PIR Sensor", HdlService.getComponent(1, 31, 317, "Kitchen storage").getDescription());
        assertEquals("Touch panel with display", HdlService.getComponent(1, 32, 9104, null).getDescription());
        assertEquals("IP module", HdlService.getComponent(1, 0, 9600, null).getDescription());
        assertEquals("Unknown device", HdlService.getComponent(1, 1, 0x7FFF, null).getDescription());
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
    void theServerTheAppAndTheSetupToolAreToldApart() {
        assertEquals("This server", HdlService.getComponent(11, 252, 0xFFFB, null).getDescription());
        assertEquals("Android/iOS app", HdlService.getComponent(11, 253, 0xFFFD, null).getDescription());
        assertEquals("HDL-BUS Pro Setup Tool", HdlService.getComponent(12, 254, 0xFFFE, null).getDescription());
    }

    @Test
    void theServerSendsAs11Slash252WithItsOwnDeviceType() {
        byte[] bytes = HdlService.getDefaultPackage(InetAddress.getLoopbackAddress());

        assertEquals(11, bytes[HdlService.BYTE_POSITION_SUBNET_ID] & 0xFF);
        assertEquals(252, bytes[HdlService.BYTE_POSITION_DEVICE_ID] & 0xFF);
        assertEquals(0xFFFB, HdlService.getComponentFromBytes(bytes, false, null).getDeviceType());
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
