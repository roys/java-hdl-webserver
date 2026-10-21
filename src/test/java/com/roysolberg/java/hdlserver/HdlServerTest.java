package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HdlServerTest {

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.255", "fd12:3456:789a::1", "fc00::1",
            "100.64.0.1", "100.101.102.103", "100.127.255.255", "fd7a:115c:a1e0::1"})
    void localAddresses(String address) {
        assertTrue(HdlServer.isLocalAddress(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "127.0.0.1", "::1", "2001:4860:4860::8888", "", "localhost", "example.com", "1.2.3.4:5678",
            "100.63.255.255", "100.128.0.1"})
    void nonLocalAddresses(String address) {
        assertFalse(HdlServer.isLocalAddress(address));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "192.168.1.2 |                                              | true",
            "192.168.1.2 | X-Forwarded-For=192.168.1.30                 | true",
            "192.168.1.2 | Forwarded=for=192.168.1.30;proto=http        | true",
            "192.168.1.2 | X-Real-IP=192.168.1.30                       | true",
            "192.168.1.2 | X-Forwarded-For=192.168.1.30;Via=1.1 nginx   | true",
            "192.168.1.2 | X-Forwarded-For=203.0.113.9                  | false",
            "192.168.1.2 | X-Forwarded-For=192.168.1.30, 203.0.113.9    | false",
            "192.168.1.2 | X-Real-IP=203.0.113.9                        | false",
            "192.168.1.2 | Forwarded=for=203.0.113.9                    | false",
            "192.168.1.2 | Forwarded=for=\"[2001:db8::1]:4711\"         | false",
            "192.168.1.2 | Forwarded=proto=https                        | false",
            "192.168.1.2 | Via=1.1 nginx                                | false",
            "192.168.1.2 | X-Forwarded-Proto=https                      | false",
            "192.168.1.2 | X-Forwarded-Host=hdl.example.com             | false",
            "203.0.113.9 | X-Forwarded-For=192.168.1.30                 | false",
    })
    void requestsThroughProxyAreOnlyLocalWhenForwardedForLocalAddresses(String remoteAddress, String headers, boolean expected) {
        Map<String, String> headerMap = new HashMap<>();
        if (headers != null) {
            for (String header : headers.split(";(?=[A-Z])")) { // Header values may contain ; too
                String[] nameAndValue = header.trim().split("=", 2);
                headerMap.put(nameAndValue[0], nameAndValue[1]);
            }
        }

        assertEquals(expected, HdlServer.isLocalRequest(remoteAddress, headerMap::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.199:4567", "192.168.1.199", "raspberrypi:4567", "localhost:4567", "rpi2.local:4567", "server.lan", "server.home.arpa:4567", "[fd00::1]:4567", "RaspberryPi.Local.:4567",
            "rpi4.tail1234.ts.net:4567"})
    void allowedHostNames(String host) {
        assertTrue(HdlServer.isAllowedHost(host, Collections.emptySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example.com:4567", "192.168.1.199.attacker.example", "evil.com", ""})
    void hostNamesThatCouldBeDnsRebinding(String host) {
        assertFalse(HdlServer.isAllowedHost(host, Collections.emptySet()));
    }

    @Test
    void hostNamesCanBeAllowed() {
        Set<String> allowedHosts = HdlServer.parseAllowedHosts(" hdl.example.com, Other.Example.com ,");

        assertTrue(HdlServer.isAllowedHost("hdl.example.com:4567", allowedHosts));
        assertTrue(HdlServer.isAllowedHost("other.example.com", allowedHosts));
        assertFalse(HdlServer.isAllowedHost("another.example.com", allowedHosts));
    }

    @ParameterizedTest
    @CsvSource({
            "http, , , http",
            "https, , , https",
            "http, https, , https",
            "http, 'https, http', , https",
            "http, , 'for=192.168.1.5;proto=https', https",
            "http, , 'for=192.168.1.5;proto=http', http",
            "http, ftp, , http",
    })
    void examplesUseHttpsWhenThePageWasOpenedOverHttps(String scheme, String xForwardedProto, String forwarded, String expected) {
        assertEquals(expected, HdlServer.shownScheme(scheme, xForwardedProto, forwarded));
    }

    @Test
    void thePortIs4567UnlessSetOtherwise() {
        assertEquals(4567, HdlServer.parsePort(null));
        assertEquals(4567, HdlServer.parsePort(" "));
        assertEquals(8080, HdlServer.parsePort(" 8080 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "80a", "http"})
    void aMistypedPortStopsTheServer(String port) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> HdlServer.parsePort(port));

        assertTrue(exception.getMessage().contains("hdlserver.port"));
    }

    @ParameterizedTest
    @CsvSource({
            "http://192.168.1.199:4567, 192.168.1.199:4567, true",
            "http://192.168.1.199:4567/settings, 192.168.1.199:4567, true",
            "http://RPI2.local:4567/settings, rpi2.local:4567, true",
            "http://evil.com, 192.168.1.199:4567, false",
            "http://192.168.1.199:4568, 192.168.1.199:4567, false",
            "null, 192.168.1.199:4567, false",
    })
    void sameOrigin(String originOrReferer, String host, boolean expected) {
        assertEquals(expected, HdlServer.isSameOrigin(originOrReferer, host));
    }

    @Test
    void requestsWithoutOriginOrRefererAreNotSameOrigin() {
        assertFalse(HdlServer.isSameOrigin(null, "192.168.1.199:4567"));
    }

    @Test
    void authToken() {
        assertTrue(HdlServer.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(HdlServer.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXY", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(HdlServer.isValidAuthToken("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(HdlServer.isValidAuthToken("", ""));
        assertFalse(HdlServer.isValidAuthToken(null, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(HdlServer.isValidAuthToken(12345L, "12345"));
        assertFalse(HdlServer.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXYZ", null));
    }

    @Test
    void percent() {
        assertEquals(30, HdlServer.parsePercent(30L));
        assertEquals(31, HdlServer.parsePercent(30.6));
        assertEquals(40, HdlServer.parsePercent("40"));
        assertEquals(40, HdlServer.parsePercent(" 40 % "));
        assertEquals(100, HdlServer.parsePercent(250L));
        assertEquals(0, HdlServer.parsePercent(-1L));
        assertThrows(HaltException.class, () -> HdlServer.parsePercent(null));
        assertThrows(HaltException.class, () -> HdlServer.parsePercent("bright"));
        assertThrows(HaltException.class, () -> HdlServer.parsePercent(true));
    }

    @Test
    void apiPaths() {
        assertTrue(HdlServer.isApiPath("/api/actions/1"));
        assertFalse(HdlServer.isApiPath("/apiary"));
        assertFalse(HdlServer.isApiPath("/settings"));
        assertFalse(HdlServer.isApiPath(null));
    }

}
