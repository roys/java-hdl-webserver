package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RelayAllowListTest {

    /** The app's send list: what it sends, to whom and how. */
    private static final Set<Integer> APP_SENDS = Set.of(
            // Controls
            0x0031, 0x193A, 0x1946, 0x19B0, 0x1C5C, 0xD912, 0xE01C, 0xE0EE, 0xE3D8, 0xE3E0,
            // Reads
            0x0033, 0x011E, 0x0248, 0x15CE, 0x1604, 0x1645, 0x1900, 0x1938, 0x1944, 0x1948, 0x194C, 0x196A, 0x198C, 0x199E,
            0x19AA, 0x19AE, 0x19B2, 0x19B6, 0x1C00, 0x1C5E, 0x1D02, 0x3007, 0x3009, 0x3513, 0x3591, 0x3593, 0x359F, 0x35A3,
            0x35B1, 0xA008, 0xC04E, 0xC072, 0xC090, 0xC100, 0xC102, 0xD205, 0xD210, 0xD902, 0xD904, 0xD908, 0xD90A, 0xD90C,
            0xD90E, 0xD914, 0xD916, 0xD91A, 0xD920, 0xD962, 0xD964, 0xDA00, 0xDA14, 0xDA18, 0xDA1C, 0xDA28, 0xDA2C, 0xDA38,
            0xDA3C, 0xDB00, 0xE004, 0xE008, 0xE0EC, 0xE120, 0xE124, 0xE12C, 0xE14C, 0xE3DA, 0xE3E7, 0xE44A, 0xE468, 0xE548,
            0xF00E, 0xF037, 0xF112,
            // The search, and a logic module's clock
            0x000E, 0xDA02);

    /** What the app's code says never to send. */
    private static final List<Integer> NEVER = List.of(0x1C1A, 0x0104, 0x010C, 0xDA16, 0xDA1A, 0xDA1E, 0xDA2A, 0xDA2E, 0xDA32,
            0xDA3A, 0xE496, 0xF116, 0x358B, 0x358F, 0x3595, 0x3599, 0x35A1, 0x35A5, 0x35A7, 0xE41E, 0xDA42, 0xD910, 0xD918);

    private static final int IR_EMITTER = 0x013F;

    @Test
    void exactlyWhatTheAppSendsIsAllowed() {
        assertEquals(85, APP_SENDS.size());
        assertEquals(APP_SENDS, RelayAllowList.ALLOWED);
        for (int operationCode = 0; operationCode <= 0xFFFF; operationCode++) {
            String refusal = RelayAllowList.INSTANCE.refusal(packet(operationCode, 1, 20), (subnet, device) -> List.of(IR_EMITTER));
            assertEquals(APP_SENDS.contains(operationCode), refusal == null, String.format("0x%04X: %s", operationCode, refusal));
        }
        for (int operationCode : NEVER) {
            assertNotNull(RelayAllowList.INSTANCE.refusal(packet(operationCode, 1, 20), (subnet, device) -> List.of(IR_EMITTER)), String.format("0x%04X", operationCode));
        }
    }

    @Test
    void onlyTheSearchGoesToManyDevices() {
        for (int operationCode : APP_SENDS) {
            boolean isSearch = operationCode == 0x000E;
            assertEquals(isSearch, allowed(packet(operationCode, 255, 255)), String.format("0x%04X to 255/255", operationCode));
            assertEquals(isSearch, allowed(packet(operationCode, 1, 255)), String.format("0x%04X to 1/255", operationCode));
            assertEquals(isSearch, allowed(packet(operationCode, 255, 20)), String.format("0x%04X to 255/20", operationCode));
        }
    }

    @Test
    void onlySmsReadsAreBigFrames() {
        for (int operationCode : APP_SENDS) {
            assertEquals(operationCode == 0xC100, allowed(bigPacket(operationCode)), String.format("0x%04X", operationCode));
        }
    }

    @Test
    void irCodesOnlyGoToAKnownIrEmitterAlone() {
        for (int operationCode : new int[]{0xD912, 0xD914, 0xD916}) {
            byte[] packet = packet(operationCode, 1, 20);
            assertNull(RelayAllowList.INSTANCE.refusal(packet, (subnet, device) -> List.of(IR_EMITTER)));
            assertNotNull(RelayAllowList.INSTANCE.refusal(packet, (subnet, device) -> List.of()), "Not found");
            assertNotNull(RelayAllowList.INSTANCE.refusal(packet, (subnet, device) -> List.of(0x0380)), "A power meter");
            assertNotNull(RelayAllowList.INSTANCE.refusal(packet, (subnet, device) -> List.of(IR_EMITTER, 0x0380)), "Sharing its address");
        }
        assertNull(RelayAllowList.INSTANCE.refusal(packet(0xD91A, 1, 20), (subnet, device) -> List.of()), "Not one of them");
    }

    private static boolean allowed(byte[] packet) {
        return RelayAllowList.INSTANCE.refusal(packet, (subnet, device) -> List.of(IR_EMITTER)) == null;
    }

    private static byte[] packet(int operationCode, int targetSubnet, int targetDevice) {
        byte[] packet = new byte[16 + 11];
        System.arraycopy("HDLMIRACLE".getBytes(StandardCharsets.US_ASCII), 0, packet, 4, 10);
        packet[14] = (byte) 0xAA;
        packet[15] = (byte) 0xAA;
        packet[16] = 11;
        packet[17] = 11;
        packet[18] = (byte) 253;
        packet[19] = (byte) 0xFF;
        packet[20] = (byte) 0xFD;
        packet[21] = (byte) (operationCode >> 8);
        packet[22] = (byte) operationCode;
        packet[23] = (byte) targetSubnet;
        packet[24] = (byte) targetDevice;
        HdlFrame.writeCrc(packet);
        return packet;
    }

    private static byte[] bigPacket(int operationCode) {
        byte[] packet = Arrays.copyOf(packet(operationCode, 1, 20), 27 + 8);
        packet[16] = (byte) 0xFF;
        packet[26] = 8;
        return packet;
    }
}
