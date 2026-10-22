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

    private static final AppCertificate CERTIFICATE = AppCertificate.create();
    private final List<AppServer> servers = new ArrayList<>();

    @AfterEach
    void closeServers() {
        servers.forEach(AppServer::close);
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

        AppServer second = new AppServer(CERTIFICATE, "server-2", "2.2.0");
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
    void aPhonePairsOverTheAppPort() throws Exception {
        Pairing pairing = new Pairing(CERTIFICATE.getCertificate().getEncoded());
        AppServer server = start(address -> true, pairing);
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String ask = "{\"name\":\"Pixel 9\",\"commitment\":\"" + Pairing.base64(Pairing.commitment(nonce)) + "\"}";

        assertEquals(new Response(409, "{\"status\":\"closed\"}"), post(server, AppServer.PAIR_PATH, ask), "Not opened on Settings");

        pairing.open();
        JsonNode created = json(post(server, AppServer.PAIR_PATH, ask), 200);
        String requestId = created.get("requestId").asText();
        String revealed = "{\"requestId\":\"" + requestId + "\",\"nonce\":\"" + Pairing.base64(nonce) + "\"}";
        assertEquals("waiting", json(post(server, AppServer.PAIR_REVEAL_PATH, revealed), 200).get("status").asText());
        assertEquals(new Response(200, "{\"status\":\"waiting\"}"), post(server, AppServer.PAIR_POLL_PATH, revealed));

        // What the app shows, worked out from the certificate it was shown
        HttpsURLConnection info = connect(server, AppServer.INFO_PATH);
        info.connect();
        byte[] shownCertificate = info.getServerCertificates()[0].getEncoded();
        info.disconnect();
        String code = Pairing.code(nonce, Pairing.decode(created.get("nonce").asText(), 32), shownCertificate);
        assertInstanceOf(Pairing.Typed.Allowed.class, pairing.type(code));

        JsonNode allowed = json(post(server, AppServer.PAIR_POLL_PATH, revealed), 200);
        assertEquals("allowed", allowed.get("status").asText());
        assertEquals("server-1", allowed.get("serverId").asText());
        assertNotNull(Pairing.decode(allowed.get("key").asText(), 32));
        assertEquals(new Response(409, "{\"status\":\"closed\"}"), post(server, AppServer.PAIR_PATH, ask), "Closed again");
    }

    @Test
    void aFullWindowIsSaidSo() throws Exception {
        Pairing pairing = new Pairing(CERTIFICATE.getCertificate().getEncoded());
        AppServer server = start(address -> true, pairing);
        pairing.open();

        for (int i = 0; i < Pairing.MAX_REQUESTS; i++) {
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            String ask = "{\"name\":\"Phone\",\"commitment\":\"" + Pairing.base64(Pairing.commitment(nonce)) + "\"}";
            assertEquals(200, post(server, AppServer.PAIR_PATH, ask).status());
        }
        String ask = "{\"name\":\"Phone\",\"commitment\":\"" + Pairing.base64(new byte[32]) + "\"}";
        assertEquals(new Response(409, "{\"status\":\"full\"}"), post(server, AppServer.PAIR_PATH, ask));
    }

    @Test
    void pairingRequestsThatArentValidAreRefused() throws Exception {
        Pairing pairing = new Pairing(CERTIFICATE.getCertificate().getEncoded());
        AppServer server = start(address -> true, pairing);
        pairing.open();
        String commitment = Pairing.base64(new byte[32]);

        for (String body : new String[]{
                "", "not json", "[]", "{}", "{\"name\":\"Pixel\"}", "{\"name\":7,\"commitment\":\"" + commitment + "\"}",
                "{\"name\":\"Pixel\\u202E9\",\"commitment\":\"" + commitment + "\"}",
                "{\"name\":\"Pixel\",\"commitment\":\"" + Pairing.base64(new byte[31]) + "\"}",
        }) {
            assertEquals(400, post(server, AppServer.PAIR_PATH, body).status(), body);
        }
        for (String path : new String[]{AppServer.PAIR_REVEAL_PATH, AppServer.PAIR_POLL_PATH}) {
            String requestId = Pairing.base64(new byte[16]);
            assertEquals(400, post(server, path, "{\"requestId\":\"" + requestId + "\"}").status(), path);
            assertEquals(400, post(server, path, "{\"requestId\":\"" + requestId + "\",\"nonce\":\"AAAA\"}").status(), path);
            assertEquals(400, post(server, path, "{\"requestId\":\"x\",\"nonce\":\"" + commitment + "\"}").status(), "Not a requestId");
            assertEquals(new Response(200, "{\"status\":\"expired\"}"),
                    post(server, path, "{\"requestId\":\"" + requestId + "\",\"nonce\":\"" + commitment + "\"}"), "Unknown");
        }
        assertEquals(Pairing.MAX_REQUESTS, pairing.window().getRequestsLeft(), "None of these counted");
    }

    private record Response(int status, String body) {
    }

    private static Response post(AppServer server, String path, String body) throws Exception {
        HttpsURLConnection connection = connect(server, path);
        connection.setRequestMethod("POST");
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
        return start(isAllowedAddress, new Pairing(CERTIFICATE.getCertificate().getEncoded()));
    }

    private AppServer start(kotlin.jvm.functions.Function1<String, Boolean> isAllowedAddress, Pairing pairing) {
        AppServer server = new AppServer(CERTIFICATE, "server-1", "2.2.0", isAllowedAddress, pairing).start(0);
        servers.add(server);
        return server;
    }

    /** Trusts only the server's own certificate, as the app does once it's paired. */
    private static SSLContext pinned() throws Exception {
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
