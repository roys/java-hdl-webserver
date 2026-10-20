package com.roysolberg.java.hdlserver.service;

import com.roysolberg.java.hdlserver.hdl.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HdlServiceSendingTest {

    private final DB database = DBMaker.memoryDB().make();

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void actionsAreSentOneAtATime() throws InterruptedException {
        RecordingHdlService hdlService = new RecordingHdlService(database, 4);
        Action first = new Action(Action.ActionType.Custom, 1, "First");
        first.addCommand(new Action.Command(1, 10, 0xE3D8, 18, 1, null));
        first.addCommand(new Action.Command(1, 11, 0xE3D8, 18, 1, null));
        Action second = new Action(Action.ActionType.Dimmer, 2, "Second");
        second.addCommand(new Action.Command(1, 20, 0x0031, 1, null, null));
        second.addCommand(new Action.Command(1, 21, 0x0031, 2, null, null));

        hdlService.performAction(first);
        hdlService.performDimmerAction(second, 50);

        assertTrue(hdlService.sent.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(10, 11, 20, 21), hdlService.targetDevices); // Not interleaved
    }

    /**
     * Records the target device of each package instead of sending it.
     */
    private static final class RecordingHdlService extends HdlService {
        final List<Integer> targetDevices = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch sent;

        RecordingHdlService(DB database, int expectedPackages) {
            super(database);
            sent = new CountDownLatch(expectedPackages);
        }

        @Override
        public InetAddress getIpAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public void sendBroadcastMessage(byte[] bytesToSend) {
            targetDevices.add(bytesToSend[BYTE_POSITION_TARGET_DEVICE_ID] & 0xFF);
            sent.countDown();
        }
    }

}
