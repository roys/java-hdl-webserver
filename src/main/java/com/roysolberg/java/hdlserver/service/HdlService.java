package com.roysolberg.java.hdlserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class HdlService {

    private static final int MULTICAST_PORT = 6000;
    private static Logger logger = LoggerFactory.getLogger(HdlService.class);

    private boolean isRunning;
    private DatagramSocket datagramSocket;

    public HdlService() {
        isRunning = true;
        new Thread(() -> {
            byte[] bytes;
            DatagramPacket packet;
            while (isRunning) {
                try {
                    final DatagramSocket datagramSocket = getDatagramSocket();
                    if (datagramSocket != null) {
                        logger.info("Receiving...");
                        bytes = new byte[105];
                        packet = new DatagramPacket(bytes, bytes.length);
                        datagramSocket.receive(packet);
                    } else {
                        logger.error("Didn't get any datagram socket. Trying again soon...");
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e1) {
                                /* no-op */
                        }
                    }
                } catch (SocketException e) {
                        /* no-op */
                } catch (IOException e) {
                    //logger.error("Got IOException while receiving packages...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                            /* no-op */
                    }
                }
            }
        }).start();
    }

    public DatagramSocket getDatagramSocket() throws SocketException {
        if (datagramSocket == null) {
            datagramSocket = new DatagramSocket(MULTICAST_PORT);
            // XXX: Set to true for non-remote access at least:
            datagramSocket.setBroadcast(true);
            // XXX: Set timeout, consider using multicast lock, check out MulticastSocket:
            // https://code.google.com/p/boxeeremote/wiki/AndroidUDP
        }
        return datagramSocket;
    }

}
