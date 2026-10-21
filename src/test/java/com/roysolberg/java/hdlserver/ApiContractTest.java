package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.service.HdlService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapdb.DB;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The API as IFTTT applets set up for earlier versions call it, against a database written by 1.0.3.
 */
class ApiContractTest {

    @TempDir
    static Path directory;

    private static DB database;
    private static RecordingHdlService hdlService;
    private static HdlServer application;

    @BeforeAll
    static void startServer() {
        database = HdlServer.openDatabase(LegacyDatabases.copyToDirectory("1.0.3", directory));
        hdlService = new RecordingHdlService(database);
        application = new HdlServer(database, hdlService).start(0);
    }

    @AfterAll
    static void stopServer() {
        application.stop();
        database.close();
    }

    @BeforeEach
    void clearRecordedCalls() {
        hdlService.calls.clear();
    }

    @Test
    void customActionIsPerformed() throws IOException {
        HttpResult result = post("/api/actions/1", "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\"}");

        assertEquals(202, result.status);
        assertEquals("{\"status\":\"ok\"}", result.body);
        assertTrue(result.contentType.startsWith("application/json"));
        assertEquals(1, hdlService.calls.size());
        assertEquals("action 1", hdlService.calls.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"auth\":\"WRONG\"}", "{\"auth\":\"\"}", "{}", "{\"auth\":42}"})
    void customActionNeedsAuthToken(String body) throws IOException {
        HttpResult result = post("/api/actions/1", body);

        assertEquals(401, result.status);
        assertTrue(hdlService.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/actions/99", "/api/actions/garage", "/api/actions/2"}) // 2 is a dimmer action
    void unknownCustomActionIsNotFound(String path) throws IOException {
        HttpResult result = post(path, "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\"}");

        assertEquals(404, result.status);
        assertTrue(hdlService.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[1,2]", ""})
    void invalidJsonIsRejected(String body) throws IOException {
        assertEquals(400, post("/api/actions/1", body).status);
        assertEquals(400, post("/api/actions/dimmer/kitchen", body).status);
        assertTrue(hdlService.calls.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "kitchen, 30, 30",
            "the%20kitchen, 30, 30",
            "Kitchen, 30, 30",
            "kitchen, 0, 0",
            "kitchen, 100, 100",
            "kitchen, 150, 100",
            "kitchen, -5, 0",
            "kitchen, '\"40\"', 40",
    })
    void dimmerActionIsPerformedByAlias(String alias, String value, String expectedValue) throws IOException {
        HttpResult result = post("/api/actions/dimmer/" + alias, "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\",\"value\":" + value + "}");

        assertEquals(202, result.status);
        assertEquals("{\"status\":\"ok\"}", result.body);
        assertEquals(1, hdlService.calls.size());
        assertEquals("dimmer 2 to " + expectedValue, hdlService.calls.get(0));
    }

    @Test
    void dimmerActionNeedsAuthToken() throws IOException {
        HttpResult result = post("/api/actions/dimmer/kitchen", "{\"auth\":\"WRONG\",\"value\":30}");

        assertEquals(401, result.status); // 1.0.x answered 500 here
        assertTrue(hdlService.calls.isEmpty());
    }

    @Test
    void dimmerActionNeedsValue() throws IOException {
        HttpResult result = post("/api/actions/dimmer/kitchen", "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\"}");

        assertEquals(400, result.status);
        assertTrue(hdlService.calls.isEmpty());
    }

    @Test
    void unknownDimmerAliasIsNotFound() throws IOException {
        HttpResult result = post("/api/actions/dimmer/garden", "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\",\"value\":30}");

        assertEquals(404, result.status);
        assertTrue(hdlService.calls.isEmpty());
    }

    /**
     * The tests reach the server from 127.0.0.1, which, like any address outside the local network, gets no web pages.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET /", "GET /actions", "GET /settings", "POST /update_settings", "POST /delete_action", "POST /search_for_components", "POST /create_custom_action", "POST /create_dimmer_action"})
    void webPagesAreNotForNonLocalAddresses(String request) throws IOException {
        String[] methodAndPath = request.split(" ");
        HttpResult result = send(methodAndPath[0], methodAndPath[1], "application/x-www-form-urlencoded", "authKey=ATTACKERCHOSENKEY0123456789ABC&actionId=1", null);

        assertEquals(401, result.status);
        assertFalse(result.body.contains(LegacyDatabases.AUTH_TOKEN));
        assertTrue(hdlService.calls.isEmpty());
        assertEquals(LegacyDatabases.AUTH_TOKEN, database.hashMap("config").createOrOpen().get(HdlServer.AUTH_TOKEN_KEY));
        assertNotNull(database.hashMap("actions").createOrOpen().get(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tooLargeBodiesAreRejectedWithoutBeingRead(boolean chunked) throws IOException {
        StringBuilder json = new StringBuilder("{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\",\"padding\":\"");
        while (json.length() < HdlServer.MAX_API_BODY_BYTES) {
            json.append("xxxxxxxxxxxxxxxx");
        }
        json.append("\"}");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + application.port() + "/api/actions/1").openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);
        if (chunked) {
            connection.setChunkedStreamingMode(1024);
        }
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The server may answer and close before the whole body is sent
        }

        assertEquals(413, connection.getResponseCode());
        assertTrue(hdlService.calls.isEmpty());
    }

    @Test
    void bodiesUpToTheLimitAreAccepted() throws IOException {
        StringBuilder json = new StringBuilder("{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\",\"padding\":\"");
        while (json.length() < HdlServer.MAX_API_BODY_BYTES - 100) {
            json.append('x');
        }
        json.append("\"}");

        assertEquals(202, post("/api/actions/1", json.toString()).status);
    }

    @Test
    void noStaticFilesAreServedOutsideTheLocalNetwork() throws IOException {
        assertEquals(401, send("GET", "/static/css/styles.css", null, null, null).status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For: 192.168.1.5", "Forwarded: for=192.168.1.5", "X-Real-IP: 192.168.1.5"})
    void forwardedHeadersCanNotMakeRequestsLocal(String header) throws IOException {
        String[] nameAndValue = header.split(": ");
        HttpResult result = send("GET", "/settings", null, null, null, nameAndValue[0], nameAndValue[1]);

        assertEquals(401, result.status);
        assertFalse(result.body.contains(LegacyDatabases.AUTH_TOKEN));
    }

    /**
     * Paths that could look like /api/ to the access check but reach a web page, or be decoded differently by the
     * check and by the router. Sent as raw request lines, so no client tidies them up first.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/settings", "/api/../settings", "/api/%2e%2e/settings", "/api/..;/settings", "/api;/../settings",
            "//settings", "/Settings", "/settings/", "/%73ettings", "/settings;jsessionid=x", "/api/actions/../../settings",
            "/api/..%2Fsettings", "/./settings", "/api%2F../settings", "/api/%2E%2E/settings", "/settings%00", "/settings?x=/api/",
            "/static/../settings", "/templates/settings.vm", "/log4j2.properties", "/api/../actions"})
    void pathTricksCanNotReachWebPages(String path) throws IOException {
        RawResponse response = sendRaw("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertFalse(response.statusLine.contains(" 200"), path + " answered " + response.statusLine);
        assertFalse(response.statusLine.matches("HTTP/1\\.1 5\\d\\d.*"), path + " answered " + response.statusLine);
        assertFalse(response.text.contains(LegacyDatabases.AUTH_TOKEN), path);
    }

    /**
     * Javalin would route by this header instead of the request's own method, so it's refused everywhere.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST /api/actions/1", "GET /api/actions/1", "GET /settings", "POST /update_settings"})
    void methodOverrideIsRefused(String request) throws IOException {
        String body = "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\"}";
        RawResponse response = sendRaw(request + " HTTP/1.1\r\nHost: 127.0.0.1\r\nX-HTTP-Method-Override: POST\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);

        assertTrue(response.statusLine.startsWith("HTTP/1.1 400"), request + " answered " + response.statusLine);
        assertTrue(hdlService.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS", "PUT", "DELETE", "PATCH", "TRACE"})
    void otherMethodsGetNoWebPagesOutsideTheLocalNetwork(String method) throws IOException {
        for (String path : new String[]{"/settings", "/update_settings", "/delete_action", "/nonexistent"}) {
            RawResponse response = sendRaw(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");

            assertTrue(response.status >= 400 && response.status < 500, method + " " + path + " answered " + response.statusLine);
            assertFalse(response.text.contains(LegacyDatabases.AUTH_TOKEN), method + " " + path);
        }
        assertEquals(LegacyDatabases.AUTH_TOKEN, database.hashMap("config").createOrOpen().get(HdlServer.AUTH_TOKEN_KEY));
        assertNotNull(database.hashMap("actions").createOrOpen().get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH"})
    void apiActionsOnlyRunOnPost(String method) throws IOException {
        String body = "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\",\"value\":30}";
        for (String path : new String[]{"/api/actions/1", "/api/actions/dimmer/kitchen"}) {
            RawResponse response = sendRaw(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);

            assertTrue(response.status >= 400 && response.status < 500, method + " " + path + " answered " + response.statusLine);
        }
        assertTrue(hdlService.calls.isEmpty());
    }

    @Test
    void unknownPathsOutsideTheLocalNetworkAreDenied() throws IOException {
        assertEquals(401, send("GET", "/nonexistent", null, null, null).status);
    }

    @Test
    void errorBodiesAreAsBefore() throws IOException {
        assertEquals("Invalid authentication token.", post("/api/actions/1", "{\"auth\":\"WRONG\"}").body);
        assertEquals("Action not found.", post("/api/actions/99", "{\"auth\":\"" + LegacyDatabases.AUTH_TOKEN + "\"}").body);
        assertEquals("{\"status\":\"error\",\"message\":\"Body must be a JSON object.\"}", post("/api/actions/1", "[]").body);
    }

    private static RawResponse sendRaw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", application.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = socket.getInputStream().read(buffer)) != -1; ) {
                response.write(buffer, 0, read);
            }
            return new RawResponse(new String(response.toByteArray(), StandardCharsets.ISO_8859_1));
        }
    }

    private static final class RawResponse {
        final String text;
        final String statusLine;
        final int status;

        RawResponse(String text) {
            this.text = text;
            this.statusLine = text.substring(0, Math.max(0, text.indexOf("\r\n")));
            String[] parts = statusLine.split(" ");
            this.status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        }
    }

    private static HttpResult post(String path, String json) throws IOException {
        return send("POST", path, "application/json", json, "application/json");
    }

    private static HttpResult send(String method, String path, String contentType, String body, String accept, String... headerNamesAndValues) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + application.port() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        for (int i = 0; i + 1 < headerNamesAndValues.length; i += 2) {
            connection.setRequestProperty(headerNamesAndValues[i], headerNamesAndValues[i + 1]);
        }
        if (accept != null) {
            connection.setRequestProperty("Accept", accept);
        }
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream inputStream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (inputStream != null) {
            try (InputStream in = inputStream) {
                byte[] buffer = new byte[4096];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    bytes.write(buffer, 0, read);
                }
            }
        }
        return new HttpResult(status, connection.getContentType(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class HttpResult {
        final int status;
        final String contentType;
        final String body;

        HttpResult(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body;
        }
    }

    /**
     * Records what would have been sent to the bus, without touching the network.
     */
    private static final class RecordingHdlService extends HdlService {
        final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingHdlService(DB database) {
            super(database);
        }

        @Override
        public void performAction(Action action) {
            calls.add("action " + action.getId());
        }

        @Override
        public void performDimmerAction(Action action, int valueInPercent) {
            calls.add("dimmer " + action.getId() + " to " + valueInPercent);
        }

        @Override
        public void doOneTimeQuickSearchForComponents() {
            calls.add("search");
        }
    }

}
