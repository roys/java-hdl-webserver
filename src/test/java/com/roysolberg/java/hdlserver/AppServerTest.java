package com.roysolberg.java.hdlserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppServerTest {

    static final AppCertificate CERTIFICATE = AppCertificate.create();
    private final List<AppServer> servers = new ArrayList<>();
    private final List<Relay> relays = new ArrayList<>();

    @AfterEach
    void closeServers() {
        servers.forEach(AppServer::close);
        relays.forEach(Relay::close);
    }

    @Test
    void theAppGetsToKnowTheServer() throws Exception {
        AppServer server = start(address -> true);

        HttpsURLConnection connection = connect(server, "/relay/info");

        assertEquals(200, connection.getResponseCode());
        assertTrue(connection.getContentType().startsWith("application/json"), connection.getContentType());
        assertEquals("{\"name\":\"Busbridge\",\"version\":\"2.2.0\",\"serverId\":\"server-1\"}", read(connection.getInputStream()));
        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
        assertEquals("no-store", connection.getHeaderField("Cache-Control"));
        assertEquals("default-src 'none'; frame-ancestors 'none'", connection.getHeaderField("Content-Security-Policy"));
        assertNull(connection.getHeaderField("Server"));
        assertEquals(CERTIFICATE.getCertificate(), connection.getServerCertificates()[0], "The pinned certificate");
    }

    @Test
    void thePagesAndTheApiArentOnTheAppPort() throws Exception {
        AppServer server = start(address -> true);

        for (String path : new String[]{"/", "/settings", "/api/actions/1", "/assets/app.css"}) {
            assertEquals(404, connect(server, path).getResponseCode(), path);
        }
    }

    @Test
    void onlyTheLocalNetworkIsAnswered() throws Exception {
        AppServer server = start(address -> false);

        assertThrows(IOException.class, () -> connect(server, "/relay/info").getResponseCode(), "Closed before the handshake");
    }

    @Test
    void oneAddressCantTakeAllTheConnections() throws Exception {
        AppServer server = start(address -> true);
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                sockets.add(new Socket("127.0.0.1", server.port())); // Saying nothing
            }
            try (Socket ninth = new Socket("127.0.0.1", server.port())) {
                ninth.setSoTimeout(5000);
                assertEquals(-1, ninth.getInputStream().read(), "Closed by the server");
            }
            sockets.remove(0).close();
            assertEquals(200, eventually(() -> connect(server, "/relay/info").getResponseCode()), "Room again");
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @Test
    void onlyCiphersThatAuthenticateAreUsed() throws Exception {
        AppServer server = start(address -> true);

        try (SSLSocket socket = (SSLSocket) pinned().getSocketFactory().createSocket("127.0.0.1", server.port())) {
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            socket.setEnabledCipherSuites(new String[]{"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"});
            assertThrows(IOException.class, socket::startHandshake);
        }
        try (SSLSocket socket = (SSLSocket) pinned().getSocketFactory().createSocket("127.0.0.1", server.port())) {
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            socket.setEnabledCipherSuites(new String[]{"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"});
            socket.startHandshake(); // What an older Android phone uses
        }
    }

    @Test
    void webPagesAreRefused() throws Exception {
        AppServer server = start(address -> true);

        // Over a raw socket, since HttpURLConnection silently drops an Origin header
        String response = sendRaw(server, "GET /relay/info HTTP/1.1\r\nHost: busbridge\r\nOrigin: https://evil.example.com\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 403"), response);
    }

    @Test
    void itsHttpsOnly() throws Exception {
        AppServer server = start(address -> true);

        HttpURLConnection plain = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/relay/info").openConnection();
        assertThrows(IOException.class, plain::getResponseCode);
    }

    @Test
    void aClientThatDoesntKnowTheCertificateCantConnect() throws Exception {
        AppServer server = start(address -> true);

        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.port() + "/relay/info").openConnection();
        connection.setHostnameVerifier((hostname, session) -> true);
        assertThrows(SSLHandshakeException.class, connection::getResponseCode, "Java's own trusted certificates");
    }

    @Test
    void aPortThatsTakenIsReportedAndLeavesNothingOpen() throws Exception {
        AppServer server = start(address -> true);

        Pairing pairing = new Pairing();
        AppServer second = new AppServer(CERTIFICATE, "server-2", "2.2.0", pairing, relay(pairing));
        assertThrows(RuntimeException.class, () -> second.start(server.port()));
        assertEquals(200, connect(server, "/relay/info").getResponseCode(), "The first still answers");
    }

    @Test
    void aClosedPortIsClosed() throws Exception {
        AppServer server = start(address -> true);
        int port = server.port();

        server.close();

        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/relay/info").openConnection();
        connection.setSSLSocketFactory(pinned().getSocketFactory());
        assertThrows(ConnectException.class, connection::getResponseCode);
    }

    @Test
    void aPhonePairsWithTheLinksToken() throws Exception {
        Pairing pairing = new Pairing();
        AppServer server = start(address -> true, pairing);
        String closed = "{\"status\":\"closed\"}";
        assertEquals(new Response(409, closed), post(server, AppServer.PAIR_PATH, pair(Pairing.base64Url(new byte[16]))), "Not opened on Settings");

        String token = pairing.open().getToken();
        assertEquals(new Response(409, closed), post(server, AppServer.PAIR_PATH, pair(Pairing.base64Url(new byte[16]))), "Not its token");
        JsonNode paired = json(post(server, AppServer.PAIR_PATH, pair(token)), 200);

        assertNotNull(Pairing.decode(paired.get("key").asText(), 32));
        assertEquals("server-1", paired.get("serverId").asText());
        assertEquals(2, paired.size());
        assertEquals(new Response(409, closed), post(server, AppServer.PAIR_PATH, pair(token)), "Used up");
    }

    @Test
    void pairingRequestsThatArentValidAreRefused() throws Exception {
        Pairing pairing = new Pairing();
        AppServer server = start(address -> true, pairing);
        String token = pairing.open().getToken();

        for (String body : new String[]{
                "", "not json", "[]", "{}", "{\"name\":\"Pixel\"}", "{\"token\":\"" + token + "\"}",
                "{\"name\":7,\"token\":\"" + token + "\"}",
                "{\"name\":\"Pixel\\u202E9\",\"token\":\"" + token + "\"}",
                "{\"name\":\"Pixel\",\"token\":\"" + token + "==\"}",
                "{\"name\":\"Pixel\",\"token\":\"" + Pairing.base64Url(new byte[15]) + "\"}",
                "{\"name\":\"Pixel\",\"token\":\"" + Pairing.base64(Pairing.decodeUrl(token, 16)) + "\"}",
        }) {
            assertEquals(400, post(server, AppServer.PAIR_PATH, body).status(), body);
        }
        assertEquals(token, pairing.window().getToken(), "Still open");
    }

    @Test
    void forgettingNeedsAPairedPhonesKey() throws Exception {
        AppServer server = start(address -> true);

        assertEquals(401, post(server, AppServer.FORGET_PATH, "", null).status());
        assertEquals(401, post(server, AppServer.FORGET_PATH, "", "Bearer " + Pairing.base64(new byte[32])).status(), "Unknown");
        assertEquals(401, post(server, AppServer.FORGET_PATH, "", "Bearer x").status());
    }

    private static String pair(String token) {
        return "{\"name\":\"Pixel 9\",\"token\":\"" + token + "\"}";
    }

    private record Response(int status, String body) {
    }

    private static Response post(AppServer server, String path, String body) throws Exception {
        return post(server, path, body, null);
    }

    private static Response post(AppServer server, String path, String body, String authorization) throws Exception {
        HttpsURLConnection connection = connect(server, path);
        connection.setRequestMethod("POST");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        try {
            int status = connection.getResponseCode();
            return new Response(status, read(status < 400 ? connection.getInputStream() : connection.getErrorStream()));
        } finally {
            connection.disconnect(); // Or the address soon has all the connections it may have
        }
    }

    private static JsonNode json(Response response, int status) throws Exception {
        assertEquals(status, response.status(), response.body());
        return new ObjectMapper().readTree(response.body());
    }

    /** Tries for a few seconds, for the server to notice a closed connection. */
    private static int eventually(java.util.concurrent.Callable<Integer> request) throws Exception {
        long giveUpAt = System.nanoTime() + 5_000_000_000L;
        while (true) {
            try {
                return request.call();
            } catch (IOException e) {
                if (System.nanoTime() > giveUpAt) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private AppServer start(kotlin.jvm.functions.Function1<String, Boolean> isAllowedAddress) throws Exception {
        return start(isAllowedAddress, new Pairing());
    }

    private AppServer start(kotlin.jvm.functions.Function1<String, Boolean> isAllowedAddress, Pairing pairing) {
        AppServer server = new AppServer(CERTIFICATE, "server-1", "2.2.0", pairing, relay(pairing), isAllowedAddress).start(0);
        servers.add(server);
        return server;
    }

    private Relay relay(Pairing pairing) {
        Relay relay = new Relay("server-1", "2.2.0", new Phones(new java.util.HashMap<>(), () -> {
        }), pairing, new Relay.Bus() {
            @Override
            public java.net.InetAddress address() {
                return null;
            }

            @Override
            public void broadcast(byte[] packet) {
            }
        }, new ActivityLog(), List::of, (subnet, device) -> List.of());
        relays.add(relay);
        return relay;
    }

    /** Trusts only the server's own certificate, as the app does once it's paired. */
    static SSLContext pinned() throws Exception {
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("busbridge", CERTIFICATE.getCertificate());
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    private static HttpsURLConnection connect(AppServer server, String path) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.port() + path).openConnection();
        connection.setSSLSocketFactory(pinned().getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true); // The certificate is pinned instead
        return connection;
    }

    private static String sendRaw(AppServer server, String request) throws Exception {
        try (SSLSocket socket = (SSLSocket) pinned().getSocketFactory().createSocket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            return read(socket.getInputStream());
        }
    }

    private static String read(InputStream inputStream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        inputStream.transferTo(bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
