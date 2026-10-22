package com.roysolberg.java.hdlserver.service;

import com.roysolberg.java.hdlserver.BusNetwork;
import com.roysolberg.java.hdlserver.BusStats;
import com.roysolberg.java.hdlserver.HdlFrame;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int REMARK_LENGTH = 20;
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
    private static final int MAX_QUEUED_PACKETS = 500;
    /** Bus frames are small, so this is only reached by someone sending big packets to fill the computer's memory. */
    private static final long MAX_QUEUED_BYTES = 2 * 1024 * 1024;
    /** The most a UDP packet can carry, so a big frame is never cut short. */
    private static final int MAX_PACKAGE_SIZE = 65_535;
    /**
     * More devices than a bus has, so that packets made up by someone else on the network can't make the server use
     * ever more memory, or fill the Devices page.
     */
    private static final int MAX_HEARD_DEVICES = 4096;
    private static final int MAX_STORED_DEVICES = 2000;
    private static final Duration BUS_NETWORK_MAX_AGE = Duration.ofMinutes(1);
    private static final int SEARCHES = 3;
    private static final long SECONDS_BETWEEN_SEARCHES = 4;
    private final ConcurrentMap<String, HdlComponent> components;
    private final DB database;
    private final BusStats busStats = new BusStats();
    private final AtomicLong queuedBytes = new AtomicLong();
    /** The modules heard on the bus since the server started, so the ones that no longer exist stand out. */
    private final Set<String> heardComponentIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService searcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hdl-search");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean isSearching = new AtomicBoolean();
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
    private BusNetwork busNetwork;
    /** Where a device on the bus was last heard from, which tells which network the bus is on. */
    private InetAddress heardFrom;
    // Only used on the receiver thread
    private boolean hasWarnedAboutHeardDevices;
    private boolean hasWarnedAboutStoredDevices;
    /** When the bus network was last found, from System.nanoTime(), or null to look for it before the next package. */
    private Long busNetworkFoundAt;

    @SuppressWarnings("unchecked") // The database stores devices by their component id
    public HdlService(DB database) {
        this.database = database;
        components = (ConcurrentMap<String, HdlComponent>) (ConcurrentMap<?, ?>) database.hashMap("components").createOrOpen();
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
        searchForComponents();
    }

    /**
     * Searches a few times, a few seconds apart: with dozens of devices answering at once, some answers get lost. While
     * a search is going on, another isn't started.
     *
     * @return whether a search was started
     */
    public boolean searchForComponents() {
        if (!isSearching.compareAndSet(false, true)) {
            return false;
        }
        for (int i = 0; i < SEARCHES; i++) {
            searcher.schedule(this::doOneTimeQuickSearchForComponents, i * SECONDS_BETWEEN_SEARCHES, TimeUnit.SECONDS);
        }
        // Runs after the last search, since tasks due at the same time run in the order they were scheduled
        searcher.schedule(() -> isSearching.set(false), (SEARCHES - 1) * SECONDS_BETWEEN_SEARCHES, TimeUnit.SECONDS);
        return true;
    }

    protected void run() {
        isRunning = true;
        // Packets are handled on their own thread, so the socket is read again right away and a burst, like dozens of
        // search replies at once, waits here instead of in the socket's small buffer
        BlockingQueue<ReceivedPackage> blockingQueue = new ArrayBlockingQueue<>(MAX_QUEUED_PACKETS);
        new Thread(() -> {
            while (isRunning) {
                ReceivedPackage received;
                try {
                    received = blockingQueue.take();
                } catch (InterruptedException e) {
                    return;
                }
                queuedBytes.addAndGet(-received.bytes().length);
                try {
                    handlePackage(received.bytes(), received.from());
                } catch (RuntimeException e) {
                    // Thrown out of here, an exception would stop the server from hearing the bus
                    logger.error("Couldn't handle the package {}.", NetworkUtil.getNicelyFormattedBytes(received.bytes()), e);
                }
            }
        }, "hdl-receiver").start();
        new Thread(() -> {
            byte[] buffer = new byte[MAX_PACKAGE_SIZE];
            boolean isDropping = false;
            while (isRunning) {
                try {
                    final DatagramSocket datagramSocket = getDatagramSocket();
                    if (datagramSocket != null) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        datagramSocket.receive(packet);
                        int length = packet.getLength();
                        // Checked before copying, so a flood that's dropped doesn't use memory either. Only this thread
                        // adds to the queue, so there's still room when it's added.
                        if (blockingQueue.remainingCapacity() > 0 && queuedBytes.get() + length <= MAX_QUEUED_BYTES) {
                            queuedBytes.addAndGet(length);
                            blockingQueue.add(new ReceivedPackage(Arrays.copyOf(buffer, length), packet.getAddress()));
                            isDropping = false;
                        } else if (!isDropping) {
                            isDropping = true;
                            logger.warn("Too many packages waiting to be handled. Dropping new ones until there's room.");
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

    private record ReceivedPackage(byte[] bytes, InetAddress from) {
    }

    /**
     * @param from where the package came from
     */
    protected void handlePackage(byte[] bytes, InetAddress from) {
        HdlFrame.Validity validity = HdlFrame.validate(bytes);
        if (validity != HdlFrame.Validity.VALID) {
            logger.warn("Received an invalid package ({}): {}", validity, NetworkUtil.getNicelyFormattedBytes(bytes));
            return;
        }
        busStats.record();
        final boolean isSearchResponse = isOperationCode(bytes, OPERATE_CODE_SEARCH_RESPONSE);
        final HdlComponent hdlComponent = getComponentFromBytes(bytes, isSearchResponse, "ISO-8859-1");
        if (hdlComponent.getDeviceType() != SENDER_DEVICE_TYPE) {
            heardFrom(from);
        }
        String componentId = HdlUtil.getComponentId(hdlComponent);
        if (heardComponentIds.size() < MAX_HEARD_DEVICES || heardComponentIds.contains(componentId)) {
            heardComponentIds.add(componentId);
        } else if (!hasWarnedAboutHeardDevices) {
            hasWarnedAboutHeardDevices = true;
            logger.warn("Heard more than {} devices, so new ones aren't noted as heard.", MAX_HEARD_DEVICES);
        }
        if (isSearchResponse) {
            if (storeFoundDevice(components, hdlComponent, heardComponentIds, MAX_STORED_DEVICES)) {
                database.commit();
            } else if (!hasWarnedAboutStoredDevices && components.size() >= MAX_STORED_DEVICES && !components.containsKey(componentId)) {
                hasWarnedAboutStoredDevices = true;
                logger.warn("Already {} devices stored, so new ones aren't. Remove old ones on the Devices page.", MAX_STORED_DEVICES);
            }
        }
        logger.info(HdlUtil.getNicelyDescribedBytes(bytes, hdlComponent));
    }

    /**
     * A device on the bus was heard from this address. If it isn't on the network the bus was thought to be on, the
     * network is looked for again before the next package is sent.
     */
    protected synchronized void heardFrom(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return;
        }
        heardFrom = address;
        if (busNetwork != null && !busNetwork.contains(address)) {
            busNetworkFoundAt = null;
        }
    }

    protected synchronized DatagramSocket getDatagramSocket() throws SocketException {
        if (datagramSocket == null) {
            datagramSocket = new DatagramSocket(MULTICAST_PORT);
            datagramSocket.setBroadcast(true);
        }
        return datagramSocket;
    }

    /**
     * @return this server's address on the bus network, which goes in every package it sends, or null without one
     */
    public InetAddress getIpAddress() {
        BusNetwork busNetwork = getBusNetwork();
        return busNetwork != null ? busNetwork.getAddress() : null;
    }

    /**
     * Looks for the bus network again now and then, since the computer's address on it can change. Until one is found,
     * e.g. right after the computer started, it's looked for again before every package.
     */
    protected synchronized BusNetwork getBusNetwork() {
        long now = System.nanoTime();
        Long foundAt = busNetworkFoundAt;
        if (foundAt == null || now - foundAt > BUS_NETWORK_MAX_AGE.toNanos()) {
            BusNetwork found = BusNetwork.find(heardFrom);
            if (found == null) {
                logger.warn("Found no network with an IPv4 broadcast address to reach the bus on.");
            } else if (!found.equals(busNetwork)) {
                logger.info("The bus is reached on {}.", found);
            }
            busNetwork = found;
            busNetworkFoundAt = found != null ? now : null;
        }
        return busNetwork;
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

    public Set<String> getHeardComponentIds() {
        return Collections.unmodifiableSet(heardComponentIds);
    }

    /**
     * Stores what a search reply says about a device: that it's new, renamed, or now known by its type. A device's key
     * includes its type. If another type was stored for its address and hasn't been heard since the server started,
     * the device was replaced, and the new one takes its place. If both are heard, two devices share an address, which
     * is a mistake in their setup, so both are kept for the Devices page to show (like HDL's setup tool does).
     *
     * @param heard the devices heard since the server started
     * @param maxDevices the most devices to store; a new one that doesn't replace another isn't stored beyond that
     * @return whether anything was stored or removed
     */
    protected static boolean storeFoundDevice(Map<String, HdlComponent> components, HdlComponent found, Set<String> heard, int maxDevices) {
        String componentId = HdlUtil.getComponentId(found);
        String addressPrefix = found.getSubnet() + "-" + found.getDeviceId() + "-";
        List<String> replaced = new ArrayList<>();
        for (String key : components.keySet()) {
            if (key.startsWith(addressPrefix) && !key.equals(componentId) && !heard.contains(key)) {
                replaced.add(key);
            }
        }
        if (!components.containsKey(componentId) && components.size() - replaced.size() >= maxDevices) {
            return false;
        }
        replaced.forEach(components::remove);
        if (!replaced.isEmpty() || isNewOrChanged(components.get(componentId), found)) {
            components.put(componentId, found);
            return true;
        }
        return false;
    }

    /**
     * Whether a search reply says something new about a device with the same key: not seen before, renamed, or now
     * known by its type.
     */
    protected static boolean isNewOrChanged(HdlComponent stored, HdlComponent found) {
        return stored == null || stored.getClass() != found.getClass() || !Objects.equals(stored.getRemark(), found.getRemark());
    }

    public static HdlComponent getComponentFromBytes(byte[] bytes, boolean includeRemark, String charset) {
        int deviceType = (bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_2] & 0xFF) | (bytes[BYTE_POSITION_DEVICE_TYPE_BYTE_1] & 0xFF) << 8;
        int subnetId = (bytes[BYTE_POSITION_SUBNET_ID] & 0xFF);
        int deviceId = (bytes[BYTE_POSITION_DEVICE_ID] & 0xFF);
        String remark = null;
        if (includeRemark) {
            // The remark is the first 20 bytes of the content, or what there is of it
            remark = getCleanRemark(bytes, HdlFrame.contentPosition(bytes), Math.min(REMARK_LENGTH, HdlFrame.contentLength(bytes)), charset);
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

    /**
     * @return the address that reaches every device on the bus network, or, without one, every device on whichever network
     * the computer picks
     */
    public InetAddress getBroadcastAddress() {
        BusNetwork busNetwork = getBusNetwork();
        return busNetwork != null ? busNetwork.getBroadcast() : BusNetwork.LIMITED_BROADCAST;
    }

    protected synchronized void closeSocket() {
        busNetworkFoundAt = null;
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
            HdlFrame.writeCrc(bytesToSend);
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

        // Content: the parameters in order, up to the first one left out (actions saved before they were checked could
        // have one missing in between, which would otherwise shift the rest into the wrong place)
        for (Integer parameter : new Integer[]{command.getParameter1(), command.getParameter2(), command.getParameter3()}) {
            if (parameter == null) {
                break;
            }
            bytesToSend[BYTE_POSITION_CONTENT + messageLength - 11] = (byte) parameter.intValue();
            messageLength++;
        }

        bytesToSend[BYTE_POSITION_LENGTH] = (byte) messageLength;
        setTarget(bytesToSend, command);
        HdlFrame.writeCrc(bytesToSend);
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
        HdlFrame.writeCrc(bytesToSend);
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
