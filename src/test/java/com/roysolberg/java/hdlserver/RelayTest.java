package com.roysolberg.java.hdlserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RelayTest {

    private static final InetAddress BUSBRIDGE_ADDRESS = address(192, 0, 2, 10);
    private final Map<String, String> phoneMap = new ConcurrentHashMap<>();
    private final Phones phones = new Phones(phoneMap, () -> {
    });
    private Pairing pairing;
    private final BlockingQueue<byte[]> sentToBus = new LinkedBlockingQueue<>();
    private final ActivityLog activityLog = new ActivityLog();
    private Relay relay;
    private AppServer server;

    @BeforeEach
    void start() throws Exception {
        pairing = new Pairing(AppServerTest.CERTIFICATE.getCertificate().getEncoded());
        Relay.Bus bus = new Relay.Bus() {
            @Override
            public InetAddress address() {
                return BUSBRIDGE_ADDRESS;
            }

            @Override
            public void broadcast(byte[] packet) {
                sentToBus.add(packet);
            }
        };
        relay = new Relay("server-1", "2.2.0", phones, pairing, bus, activityLog, () -> List.of("192.0.2.10", "100.64.0.10"));
        server = new AppServer(AppServerTest.CERTIFICATE, "server-1", "2.2.0", pairing, relay, address -> true).start(0);
    }

    @AfterEach
    void stop() {
        server.close();
        relay.close();
    }

    @Test
    void aPairedPhoneGetsToKnowBusbridge() throws Exception {
        byte[] key = pairedKey("Pixel 9");

        try (TestWebSocket phone = connect(key)) {
            assertEquals(101, phone.status);
            JsonNode hello = new ObjectMapper().readTree(phone.read(TestWebSocket.TEXT).text());
            assertEquals("2.2.0", hello.get("version").asText());
            assertEquals("server-1", hello.get("serverId").asText());
            assertEquals("[\"192.0.2.10\",\"100.64.0.10\"]", hello.get("addresses").toString());
        }
        assertTrue(activityLog.entries().stream().anyMatch(entry -> entry.getResult().equals("Connected") && entry.getSource().startsWith("Phone \"Pixel 9\"")));
    }

    @Test
    void theFirstConnectionCompletesThePairing() throws Exception {
        pairing.open();
        byte[] nonce = random(32);
        Pairing.Asked.Created asked = (Pairing.Asked.Created) pairing.ask("Pixel 9", Pairing.commitment(nonce));
        pairing.reveal(asked.getRequestId(), nonce);
        pairing.type(Pairing.code(nonce, asked.getNonce(), AppServerTest.CERTIFICATE.getCertificate().getEncoded()));
        byte[] key = pairing.poll(asked.getRequestId(), nonce).getKey();
        assertTrue(phones.all().isEmpty(), "Not paired before it connects");

        try (TestWebSocket phone = connect(key)) {
            assertEquals(101, phone.status);
        }

        assertEquals("Pixel 9", phones.all().get(0).getName());
        assertEquals(Pairing.Status.EXPIRED, pairing.poll(asked.getRequestId(), nonce).getStatus(), "The key isn't kept any longer");
        assertFalse(phoneMap.values().iterator().next().contains(Pairing.base64(key)), "Only its hash is saved");
        try (TestWebSocket again = connect(key)) {
            assertEquals(101, again.status, "And it connects again as a paired phone");
        }
    }

    @Test
    void onlyAPairedPhonesKeyGetsIn() throws Exception {
        String unknown = Pairing.base64(random(32));
        for (String authorization : new String[]{null, "Bearer " + unknown, "Bearer x", "Basic " + unknown, "bearer " + unknown, unknown}) {
            try (TestWebSocket phone = TestWebSocket.connect(server.port(), authorization == null ? List.of() : List.of("Authorization: " + authorization))) {
                assertEquals(401, phone.status, authorization);
            }
        }
    }

    @Test
    void webPagesCantConnect() throws Exception {
        byte[] key = pairedKey("Pixel 9");

        try (TestWebSocket page = TestWebSocket.connect(server.port(), List.of("Authorization: Bearer " + Pairing.base64(key), "Origin: https://evil.example.com"))) {
            assertEquals(403, page.status);
        }
    }

    @Test
    void packetsFromThePhoneGoToTheBusFromBusbridge() throws Exception {
        try (TestWebSocket phone = connect(pairedKey("Pixel 9"))) {
            phone.read(TestWebSocket.TEXT);
            byte[] packet = packet(0x0031);
            byte[] withMore = Arrays.copyOf(packet, packet.length + 5);
            byte[] damaged = packet.clone();
            damaged[damaged.length - 1]++;

            phone.send(TestWebSocket.BINARY, damaged);
            phone.send(TestWebSocket.BINARY, "not HDL".getBytes(StandardCharsets.US_ASCII));
            phone.send(TestWebSocket.BINARY, withMore);

            byte[] sent = sentToBus.poll(5, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertArrayEquals(BUSBRIDGE_ADDRESS.getAddress(), Arrays.copyOf(sent, 4), "Busbridge's address, so devices answer it");
            assertArrayEquals(Arrays.copyOfRange(packet, 4, packet.length), Arrays.copyOfRange(sent, 4, sent.length), "Without what came after the frame");
            assertNull(sentToBus.poll(200, TimeUnit.MILLISECONDS), "The damaged packets weren't sent");
        }
    }

    @Test
    void eachPhoneSendsAtMost20PacketsASecond() throws Exception {
        try (TestWebSocket phone = connect(pairedKey("Pixel 9"))) {
            phone.read(TestWebSocket.TEXT);
            long startedAt = System.nanoTime();
            for (int i = 0; i < 30; i++) {
                phone.send(TestWebSocket.BINARY, packet(0x0031));
            }
            for (int i = 0; i < 30; i++) {
                assertNotNull(sentToBus.poll(5, TimeUnit.SECONDS), "Queued, not dropped");
            }
            assertTrue(System.nanoTime() - startedAt >= TimeUnit.MILLISECONDS.toNanos(1400), "30 packets take about 1.5 s");
        }
    }

    @Test
    void whatTheBusSaysGoesToThePhone() throws Exception {
        try (TestWebSocket phone = connect(pairedKey("Pixel 9"))) {
            phone.read(TestWebSocket.TEXT);
            byte[] packet = packet(0x0032);

            relay.forward(packet);

            assertArrayEquals(packet, phone.read(TestWebSocket.BINARY).payload());
        }
    }

    @Test
    void aRemovedPhoneIsToldSoAndCantComeBack() throws Exception {
        byte[] key = pairedKey("Pixel 9");
        try (TestWebSocket phone = connect(key)) {
            phone.read(TestWebSocket.TEXT);

            String id = phones.all().get(0).getId();
            phones.remove(id);
            relay.disconnect(id);

            assertEquals(Relay.REMOVED, phone.read(TestWebSocket.CLOSE).closeCode());
        }
        try (TestWebSocket again = connect(key)) {
            assertEquals(401, again.status);
        }
    }

    @Test
    void aPhoneHasOneConnection() throws Exception {
        byte[] key = pairedKey("Pixel 9");
        try (TestWebSocket old = connect(key); TestWebSocket newer = connect(key)) {
            old.read(TestWebSocket.TEXT);
            newer.read(TestWebSocket.TEXT);

            assertEquals(1000, old.read(TestWebSocket.CLOSE).closeCode());
            relay.forward(packet(0x0032));
            assertEquals(TestWebSocket.BINARY, newer.read(TestWebSocket.BINARY).opcode());
        }
    }

    @Test
    void atMostFivePhonesAtATime() throws Exception {
        List<TestWebSocket> connected = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                TestWebSocket phone = connect(pairedKey("Phone " + i));
                phone.read(TestWebSocket.TEXT);
                connected.add(phone);
            }
            try (TestWebSocket sixth = connect(pairedKey("Phone 6"))) {
                assertEquals(1013, sixth.read(TestWebSocket.CLOSE).closeCode());
            }
        } finally {
            for (TestWebSocket phone : connected) {
                phone.close();
            }
        }
    }

    private byte[] pairedKey(String name) {
        byte[] key = random(32);
        assertNotNull(phones.add(name, key, Instant.now()));
        return key;
    }

    private TestWebSocket connect(byte[] key) throws Exception {
        return TestWebSocket.connect(server.port(), List.of("Authorization: Bearer " + Pairing.base64(key)));
    }

    /** A valid packet from the app, 11/253, to every device. */
    private static byte[] packet(int operationCode) {
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
        packet[23] = (byte) 0xFF;
        packet[24] = (byte) 0xFF;
        HdlFrame.writeCrc(packet);
        return packet;
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static InetAddress address(int... bytes) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) bytes[0], (byte) bytes[1], (byte) bytes[2], (byte) bytes[3]});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A WebSocket client just big enough for these tests, trusting only Busbridge's certificate, like the app. */
    static final class TestWebSocket implements AutoCloseable {
        static final int TEXT = 1;
        static final int BINARY = 2;
        static final int CLOSE = 8;
        private static final int PING = 9;
        private static final int PONG = 10;

        /** The upgrade's HTTP status: 101 when connected. */
        final int status;
        private final SSLSocket socket;
        private final DataInputStream in;
        private final OutputStream out;

        record Frame(int opcode, byte[] payload) {
            String text() {
                return new String(payload, StandardCharsets.UTF_8);
            }

            int closeCode() {
                return (payload[0] & 0xFF) << 8 | payload[1] & 0xFF;
            }
        }

        private TestWebSocket(SSLSocket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            String statusLine = readLine();
            status = Integer.parseInt(statusLine.split(" ")[1]);
            while (!readLine().isEmpty()) {
                // The headers
            }
        }

        static TestWebSocket connect(int port, List<String> headers) throws Exception {
            SSLSocket socket = (SSLSocket) AppServerTest.pinned().getSocketFactory().createSocket("127.0.0.1", port);
            StringBuilder request = new StringBuilder("GET " + AppServer.RELAY_PATH + " HTTP/1.1\r\nHost: busbridge\r\n")
                    .append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n");
            headers.forEach(header -> request.append(header).append("\r\n"));
            socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            return new TestWebSocket(socket);
        }

        /** Sends one frame, masked with zeros, which leaves the payload as it is. */
        void send(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(0x80 | payload.length);
            } else {
                frame.write(0x80 | 126);
                frame.write(payload.length >> 8);
                frame.write(payload.length);
            }
            frame.write(new byte[4]);
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        /** The next frame, which must be of this kind. Pings are answered on the way. */
        Frame read(int expectedOpcode) throws IOException {
            while (true) {
                int opcode = in.readUnsignedByte() & 0x0F;
                int length = in.readUnsignedByte() & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = (int) in.readLong();
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (opcode == PING) {
                    send(PONG, payload);
                    continue;
                }
                assertEquals(expectedOpcode, opcode, "The kind of frame");
                return new Frame(opcode, payload);
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != '\n') {
                if (b == -1) {
                    throw new IOException("Closed");
                }
                if (b != '\r') {
                    line.write(b);
                }
            }
            return line.toString(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
