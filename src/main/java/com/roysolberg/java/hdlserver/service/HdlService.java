package com.roysolberg.java.hdlserver.service;

import com.roysolberg.java.hdlserver.BusStats;
import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponentFactory;
import com.roysolberg.java.hdlserver.hdl.component.UnsupportedComponent;
import com.roysolberg.java.hdlserver.util.HdlUtil;
import com.roysolberg.java.hdlserver.util.NetworkUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.DB;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class HdlService {

    protected final static int MULTICAST_PORT = 6000;
    protected final static int BYTE_POSITION_LENGTH = 16;
    protected final static int BYTE_POSITION_SUBNET_ID = 17;
    protected final static int BYTE_POSITION_DEVICE_ID = 18;
    protected final static int BYTE_POSITION_DEVICE_TYPE_BYTE_1 = 19;
    protected final static int BYTE_POSITION_DEVICE_TYPE_BYTE_2 = 20;
    protected final static int BYTE_POSITION_OPERATION_CODE_BYTE_1 = 21;
    protected final static int BYTE_POSITION_OPERATION_CODE_BYTE_2 = 22;
    protected final static int BYTE_POSITION_TARGET_SUBNET_ID = 23;
    protected final static int BYTE_POSITION_TARGET_DEVICE_ID = 24;
    protected final static int BYTE_POSITION_CONTENT = 25;
    protected final static int BYTE_POSITION_REMARK = 25;
    protected final static int OPERATE_CODE_SEARCH_RESPONSE = 0x000F;
    protected static final int SINGLE_CHANNEL_CONTROL = 0x0031;
    protected final static byte OPERATE_CODE_SEARCH_BYTE_1 = (byte) 0x00;
    protected final static byte OPERATE_CODE_SEARCH_BYTE_2 = (byte) 0x0E;
    protected static final byte TARGET_SUBNET_ID_ALL = (byte) 0xFF;
    protected static final byte TARGET_DEVICE_ID_ALL = (byte) 0xFF;
    // Who this server sends as. 0xFFFC is taken by HDL, 0xFFFD by the Android/iOS app and 0xFFFE by HDL's Setup Tool, so
    // with 0xFFFB the bus log (and anyone else listening) can tell them apart.
    public static final int SENDER_SUBNET_ID = 11;
    public static final int SENDER_DEVICE_ID = 252;
    public static final int SENDER_DEVICE_TYPE = 0xFFFB;
    public static final int APP_DEVICE_TYPE = 0xFFFD; // 11/253
    public static final int SETUP_TOOL_DEVICE_TYPE = 0xFFFE; // 12/254

    private static Logger logger = LogManager.getLogger("HDL");
    protected static final int MAX_QUEUED_ACTIONS = 50;
    private final ConcurrentMap components;
    private final DB database;
    private final BusStats busStats = new BusStats();
    /** One action at a time, so their commands don't interleave on the bus, and a bounded number waiting. */
    private final ExecutorService commandSender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUED_ACTIONS), runnable -> {
        Thread thread = new Thread(runnable, "hdl-command-sender");
        thread.setDaemon(true);
        return thread;
    }, (runnable, executor) -> {
        logger.warn("Already {} actions waiting to be sent. Refusing this one.", MAX_QUEUED_ACTIONS);
        throw new RejectedExecutionException("Already " + MAX_QUEUED_ACTIONS + " actions waiting to be sent");
    });

    protected boolean isRunning;
    protected DatagramSocket datagramSocket;
    protected InetAddress localInetAddress;
    protected InetAddress broadcastInetAddress;

    public HdlService(DB database) {
        this.database = database;
        components = database.hashMap("components").createOrOpen();
        logger.info("components: {}", components.values());
    }

    public BusStats getBusStats() {
        return busStats;
    }

    /**
     * Starts listening to the bus and searches for components.
     */
    public void start() {
        run();
        doOneTimeQuickSearchForComponents();
    }

    protected void run() {
        isRunning = true;
        BlockingQueue<byte[]> blockingQueue = new ArrayBlockingQueue<>(200);
        new Thread(() -> {
            while (isRunning) {
                try {
                    while (!blockingQueue.isEmpty()) {
                        byte[] bytes = blockingQueue.take();
                        //System.out.println("Queue size: " + blockingQueue.size() + ", remaining capacity: " + blockingQueue.remainingCapacity());
                        if (isPackageValid(bytes)) {
                            busStats.record();
                            // XXX: Comment out:
                            //logger.info("Received package " + nicelyFormattedBytes + ".");
                            final boolean isSearchResponse = isOperationCode(bytes, OPERATE_CODE_SEARCH_RESPONSE);
                            final HdlComponent hdlComponent = getComponentFromBytes(bytes, isSearchResponse, "ISO-8859-1");
                            if (isSearchResponse) {
                                String componentId = HdlUtil.getComponentId(hdlComponent);
                                if (isNewOrChanged((HdlComponent) components.get(componentId), hdlComponent)) {
                                    components.put(componentId, hdlComponent);
                                    database.commit();
                                }
                            }
                            logger.info(HdlUtil.getNicelyDescribedBytes(bytes, hdlComponent));
                        } else {
                            String nicelyFormattedBytes = NetworkUtil.getNicelyFormattedBytes(bytes);
                            logger.warn("Received invalid package " + nicelyFormattedBytes);
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                    /* no-op */
                            // XXX: Comment out:
                            logger.info("InterruptedException.");
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    /* no-op */
                }
            }
        }).start();
        new Thread(() -> {
            DatagramPacket packet;
            while (isRunning) {
                try {
                    final DatagramSocket datagramSocket = getDatagramSocket();
                    if (datagramSocket != null) {
                        //logger.info("Receiving...");
                        byte[] bytes = new byte[105];
                        packet = new DatagramPacket(bytes, bytes.length);
                        datagramSocket.receive(packet);
                        try {
                            blockingQueue.add(bytes);
                        } catch (IllegalStateException e) {
                            logger.error("Got IllegalStateException while trying to add packet to queue. Emptying queue.");
                            blockingQueue.clear();
                        }
                    } else {
                        logger.error("Didn't get any datagram socket. Trying again soon...");
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e1) {
                                /* no-op */
                        }
                    }
                } catch (SocketException e) {
                    logger.error("Got SocketException while receiving packages...", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                            /* no-op */
                    }
                } catch (IOException e) {
                    logger.error("Got IOException while receiving packages...", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                            /* no-op */
                    }
                }
            }
        }).start();
    }

    protected synchronized DatagramSocket getDatagramSocket() throws SocketException {
        if (datagramSocket == null) {
            datagramSocket = new DatagramSocket(MULTICAST_PORT);
            datagramSocket.setBroadcast(true);
        }
        return datagramSocket;
    }

    public InetAddress getIpAddress() {
        if (localInetAddress == null) {
            // XXX: Remove this remote access:
//            try {
//                appInetAddress = InetAddress.getByName("176.11.85.125");
//                logger.warn(appInetAddress.getHostAddress());
//            } catch (UnknownHostException e) {
//                e.printStackTrace();
//            }
//            if(true)
//                return appInetAddress;
//            try {
//                for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
//                    NetworkInterface intf = en.nextElement();
//                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
//                        InetAddress inetAddress = enumIpAddr.nextElement();
//                        if (!inetAddress.isLoopbackAddress()) {
//                            //return inetAddress.getHostAddress().toString();
//                            appInetAddress = inetAddress;
//                            logger.warn(appInetAddress.getHostAddress());
//                            return appInetAddress;
//                        }
//                    }
//                }
//            } catch (SocketException ex) {
//                Log.e("SALMAN", ex.toString());
//            }

            Socket socket = null;
            try {
                socket = new Socket("google.com", 80);
                localInetAddress = socket.getLocalAddress();
                logger.debug("Got local IP address [" + localInetAddress + "] from socket.");
                return localInetAddress;
            } catch (IOException e) {
                logger.error("Got IOException while trying to find local address from socket.");
            } finally {
                if (socket != null && socket.isConnected()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                    /* no-op */
                    }
                }
            }
        }
        return localInetAddress;
    }

    protected static byte[] getDefaultPackage(InetAddress inetAddress) {
        byte[] bytes = new byte[92];
        // Local IP address
        if (inetAddress != null) {
            byte[] address = inetAddress.getAddress();
            bytes[0] = address[0];
            bytes[1] = address[1];
            bytes[2] = address[2];
            bytes[3] = address[3];
        } else {
            logger.error("Got no local IP address. Still trying to send package.");
        }
        // Static content when sending UDP
        bytes[4] = 'H';
        bytes[5] = 'D';
        bytes[6] = 'L';
        bytes[7] = 'M';
        bytes[8] = 'I';
        bytes[9] = 'R';
        bytes[10] = 'A';
        bytes[11] = 'C';
        bytes[12] = 'L';
        bytes[13] = 'E';
        // Static content always used in HDL-BUS Pro packages
        bytes[14] = (byte) 0xAA;
        bytes[15] = (byte) 0xAA;
        // Length
        bytes[BYTE_POSITION_LENGTH] = (byte) 0x0B; // 11 is the least it can be
        // Original subnet ID, device ID and device type
        bytes[BYTE_POSITION_SUBNET_ID] = (byte) SENDER_SUBNET_ID;
        bytes[BYTE_POSITION_DEVICE_ID] = (byte) SENDER_DEVICE_ID;
        bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_1] = (byte) (SENDER_DEVICE_TYPE >> 8);
        bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_2] = (byte) SENDER_DEVICE_TYPE;
        return bytes;
    }

    protected static void generateCrc(byte[] bytesToSend) {
        int length = bytesToSend[BYTE_POSITION_LENGTH] - 2; // Length minus CRC data
        int crc = getCrc(bytesToSend, BYTE_POSITION_LENGTH, length);
        bytesToSend[BYTE_POSITION_LENGTH + length + 1] = (byte) crc;
        bytesToSend[BYTE_POSITION_LENGTH + length] = (byte) (crc >>> 8);
    }

    /**
     * CRC-16/XMODEM (CCITT polynomial 0x1021, initial value 0), as HDL Buspro uses.
     */
    protected static int getCrc(byte[] bytes, int offset, int length) {
        int polynomial = 0x1021;   // Represents x^16+x^12+x^5+1
        int crc = 0x0000;
        for (int j = offset; j < (offset + length); j++) {
            byte b = bytes[j];
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                // If coefficient of bit and remainder polynomial = 1 xor crc with polynomial
                if (c15 ^ bit) crc ^= polynomial;
            }
        }
        return crc & 0xffff;
    }

    public static boolean isPackageValid(byte[] bytes) {
        if (bytes != null && bytes.length >= 23) {
            byte[] fixedBytes = new byte[]{'H', 'D', 'L', 'M', 'I', 'R', 'A', 'C', 'L', 'E', (byte) 0xAA, (byte) 0xAA}; // TODO: should be constant + used for default package creation + fix those 0xaa
            for (int i = 0; i < fixedBytes.length; i++) {
                if (bytes[i + 4] != fixedBytes[i]) {
                    return false;
                }
                // TODO: Verify CRC
            }
            return true;
        }
        return false;
    }

    /**
     * Whether a search reply says something new: a module not seen before, renamed, or now known by its type.
     */
    protected static boolean isNewOrChanged(HdlComponent stored, HdlComponent found) {
        return stored == null || stored.getClass() != found.getClass() || stored.getDeviceType() != found.getDeviceType()
                || !Objects.equals(stored.getRemark(), found.getRemark());
    }

    public static HdlComponent getComponentFromBytes(byte[] bytes, boolean includeRemark, String charset) {
        int deviceType = (bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_2] & 0xFF) | (bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_1] & 0xFF) << 8;
        int subnetId = (bytes[BYTE_POSITION_SUBNET_ID] & 0xFF);
        int deviceId = (bytes[BYTE_POSITION_DEVICE_ID] & 0xFF);
        String remark = null;
        if (includeRemark) {
            remark = getCleanRemark(bytes, BYTE_POSITION_REMARK, 20, charset);
        }
        return getComponent(subnetId, deviceId, deviceType, remark);
    }

    public static HdlComponent getComponent(int subnetId, int deviceId, int deviceType, String remark) {
        switch (deviceType) {
            case SENDER_DEVICE_TYPE:
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "This server");
            case APP_DEVICE_TYPE:
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "Android/iOS app");
            case SETUP_TOOL_DEVICE_TYPE:
                return new UnsupportedComponent(subnetId, deviceId, deviceType, remark, "HDL-BUS Pro Setup Tool");
            default:
                return HdlComponentFactory.getHdlComponent(subnetId, deviceId, deviceType, remark);
        }
    }

    public static boolean isOperationCode(byte[] bytes, int operationCode) {
        return isOperationCode(bytes[BYTE_POSITION_OPERATION_CODE_BYTE_1], bytes[BYTE_POSITION_OPERATION_CODE_BYTE_2], operationCode);
    }

    protected static boolean isOperationCode(byte byte1, byte byte2, int operationCode) {
        return (((byte1 & 0xff) << 8) | (byte2 & 0xff)) == operationCode;
    }

    protected static String getCleanRemark(byte[] bytes, int offset, int length, String charset) {
        int numOfInvalidBytes = 0;
        for (int i = offset; i < (length + offset); i++) {
            if ((bytes[i] & 0xff) == 0 || (bytes[i] & 0xff) == 255) {
                numOfInvalidBytes++;
            } else {
                break; // At least one ok byte
            }
        }
        if (numOfInvalidBytes == length) { // Only invalid bytes
            return null;
        }
        try {
            if (charset == null) {
                charset = "ISO-8859-1";
            }
            return new String(bytes, offset, length, charset);
        } catch (UnsupportedEncodingException e) {
            return new String(bytes, offset, length);
        }
    }

    public InetAddress getBroadcastAddress() {
        //
        // Already set address
        //
        if (broadcastInetAddress != null) {
            return broadcastInetAddress;
        }
        //
        // Default values
        //
        int netmask = 0;
        int ipAddress = 0;
        //
        // IP from socket
        //
        Socket socket = null;
        try {
            socket = new Socket("google.com", 80);
            InetAddress socketInetAddress = socket.getLocalAddress();
            logger.debug("Got local IP address [" + socketInetAddress + "] from socket when trying to find broadcast address.");
            byte[] bytes = socketInetAddress.getAddress();
            ipAddress = (
                    ((bytes[3] & 0xFF) << (3 * 8)) +
                            ((bytes[2] & 0xFF) << (2 * 8)) +
                            ((bytes[1] & 0xFF) << (1 * 8)) +
                            ((bytes[0] & 0xFF) << (0 * 8))
            );// & 0xffffffffL;
        } catch (IOException e) {
            logger.error("Got IOException while trying to find broadcast address from socket. Trying Wi-Fi manager.");
        } finally {
            if (socket != null && socket.isConnected()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    /* no-op */
                }
            }
        }
        if (ipAddress == 0) {
            try {
                return broadcastInetAddress = InetAddress.getByName("239.255.255.250");
            } catch (UnknownHostException e) {
                logger.error("Got UnknownHostException while trying to resolve multicast address. Giving up finding broadcast address.");
                return null;
            }
        }
        if (netmask == 0) {
            netmask = 16777215; // We take a wild guess at 255.255.255.0 (0.255.255.255)
//            netmask = 65535; // We take a wild guess at 255.255.0.0 (0.0.255.255)
        }
        long broadcast = (ipAddress & netmask) | ~netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        try {
            broadcastInetAddress = InetAddress.getByAddress(quads);
            logger.debug("Using [" + broadcastInetAddress + "] as broadcast address.");
            return broadcastInetAddress;
        } catch (UnknownHostException e) {
            logger.warn("Got UnknownHostException while trying to get broadcast address.", e);
        }
        return null;
    }

    protected void closeSocket() {
        broadcastInetAddress = null; // Forces service to try to get fresh broadcast address
        localInetAddress = null; // Forces service to try to get fresh local address
        if (datagramSocket != null) {
            if (!datagramSocket.isClosed()) {
                datagramSocket.close();
            }
            datagramSocket = null;
        }
    }

    public void sendBroadcastMessage(byte[] bytesToSend) {
        try {
            final DatagramPacket datagramPacket = new DatagramPacket(bytesToSend, bytesToSend.length, getBroadcastAddress(), MULTICAST_PORT);
            getDatagramSocket().send(datagramPacket);
            // XXX: Comment out:
//            logger.debug("Package " + NetworkUtil.getNicelyFormattedBytes(bytesToSend) + " sent to [" + getBroadcastAddress() + "].");
        } catch (SocketTimeoutException e) {
            logger.error("Got SocketTimeoutException while trying to send broadcast message. Notifying listener.", e);
            closeSocket();
            //sendBroadcastingError(e);
        } catch (SocketException e) {
            logger.error("Got SocketException while trying to send broadcast message. Notifying listener.", e);
            closeSocket();
            //sendBroadcastingError(e);
        } catch (IOException e) {
            logger.error("Got IOException while trying to send broadcast message. Notifying listener.", e);
            closeSocket();
            //sendBroadcastingError(e);
        } catch (NullPointerException e) {
            logger.error("Got NullPointerException while trying to send broadcast message. Notifying listener.", e);
            closeSocket();
            //sendBroadcastingError(e);
        }
    }

    public void doOneTimeQuickSearchForComponents() {
        logger.info("doOneTimeQuickSearchForComponents()"); // XXX: Remove
        if (getIpAddress() != null) {
            byte[] bytesToSend = getDefaultPackage(getIpAddress());
            // Length
            bytesToSend[BYTE_POSITION_LENGTH] = (byte) 0x0B; // 11
            // Operate code
            bytesToSend[BYTE_POSITION_OPERATION_CODE_BYTE_1] = OPERATE_CODE_SEARCH_BYTE_1;
            bytesToSend[BYTE_POSITION_OPERATION_CODE_BYTE_2] = OPERATE_CODE_SEARCH_BYTE_2;
            // Target subnet and device ids:
            bytesToSend[BYTE_POSITION_TARGET_SUBNET_ID] = TARGET_SUBNET_ID_ALL;
            bytesToSend[BYTE_POSITION_TARGET_DEVICE_ID] = TARGET_DEVICE_ID_ALL;
            generateCrc(bytesToSend);
            sendBroadcastMessage(bytesToSend);
        } else {
            logger.warn("Couldn't get any local IP address. Unable to do a quick search for components.");
        }
    }

    /**
     * Sends the commands of a custom action, e.g. "Open the garage".
     *
     * @throws RejectedExecutionException if too many actions are already waiting to be sent
     */
    public void performAction(final Action action) {
        sendCommands(action, command -> getCommandPackage(getIpAddress(), command));
    }

    /**
     * Sets every channel of a dimmer action to the given level.
     *
     * @throws RejectedExecutionException if too many actions are already waiting to be sent
     */
    public void performDimmerAction(final Action action, final int valueInPercent) {
        sendCommands(action, command -> getDimmerPackage(getIpAddress(), command, valueInPercent));
    }

    protected void sendCommands(final Action action, final Function<Action.Command, byte[]> toPackage) {
        commandSender.execute(() -> {
            for (Action.Command command : action.getCommands()) {
                sendBroadcastMessage(toPackage.apply(command));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * A command with up to three parameters as its content.
     */
    protected static byte[] getCommandPackage(InetAddress localInetAddress, Action.Command command) {
        if (command.getOperation() == SINGLE_CHANNEL_CONTROL) {
            // The channel, the level and a run time in two bytes, which the third parameter gives in seconds
            int runTime = command.getParameter3() != null ? command.getParameter3() : 0;
            return getSingleChannelPackage(localInetAddress, command, command.getParameter2() != null ? command.getParameter2() : 0, runTime);
        }
        byte[] bytesToSend = getDefaultPackage(localInetAddress);
        int messageLength = 11;

        // Content
        if (command.getParameter1() != null) {
            messageLength++;
            bytesToSend[BYTE_POSITION_CONTENT] = (byte) command.getParameter1().intValue();
        }
        if (command.getParameter2() != null) {
            messageLength++;
            bytesToSend[BYTE_POSITION_CONTENT + 1] = (byte) command.getParameter2().intValue();
        }
        if (command.getParameter3() != null) {
            messageLength++;
            bytesToSend[BYTE_POSITION_CONTENT + 2] = (byte) command.getParameter3().intValue();
        }

        bytesToSend[BYTE_POSITION_LENGTH] = (byte) messageLength;
        setTarget(bytesToSend, command);
        generateCrc(bytesToSend);
        return bytesToSend;
    }

    /**
     * A dimmer action's channel (parameter 1) to the given level, right away.
     */
    protected static byte[] getDimmerPackage(InetAddress localInetAddress, Action.Command command, int valueInPercent) {
        return getSingleChannelPackage(localInetAddress, command, valueInPercent, 0);
    }

    /**
     * Single channel control (0x0031) of the command's channel (parameter 1) to a level, over a run time in seconds.
     */
    protected static byte[] getSingleChannelPackage(InetAddress localInetAddress, Action.Command command, int valueInPercent, int runTimeInSeconds) {
        byte[] bytesToSend = getDefaultPackage(localInetAddress);
        // Channel
        if (command.getParameter1() != null) {
            bytesToSend[BYTE_POSITION_CONTENT] = (byte) command.getParameter1().intValue();
        }
        // Percent
        bytesToSend[BYTE_POSITION_CONTENT + 1] = (byte) valueInPercent;
        // Running time (high and low)
        bytesToSend[BYTE_POSITION_CONTENT + 2] = (byte) (runTimeInSeconds >> 8);
        bytesToSend[BYTE_POSITION_CONTENT + 3] = (byte) runTimeInSeconds;
        // Not sure, but the DLP sends different values for a fifth field:
        bytesToSend[BYTE_POSITION_CONTENT + 4] = (byte) 0x0F;

        bytesToSend[BYTE_POSITION_LENGTH] = (byte) 16;
        setTarget(bytesToSend, command);
        generateCrc(bytesToSend);
        return bytesToSend;
    }

    protected static void setTarget(byte[] bytesToSend, Action.Command command) {
        // Operate code
        bytesToSend[BYTE_POSITION_OPERATION_CODE_BYTE_1] = (byte) (command.getOperation() >> 8);
        bytesToSend[BYTE_POSITION_OPERATION_CODE_BYTE_2] = (byte) command.getOperation();
        // Target subnet and device ids:
        bytesToSend[BYTE_POSITION_TARGET_SUBNET_ID] = (byte) command.getSubnetId();
        bytesToSend[BYTE_POSITION_TARGET_DEVICE_ID] = (byte) command.getDeviceId();
    }

}
