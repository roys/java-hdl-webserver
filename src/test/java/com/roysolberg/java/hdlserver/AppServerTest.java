package com.roysolberg.java.hdlserver;

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

    private AppServer start(kotlin.jvm.functions.Function1<String, Boolean> isAllowedAddress) {
        AppServer server = new AppServer(CERTIFICATE, "server-1", "2.2.0", isAllowedAddress).start(0);
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
