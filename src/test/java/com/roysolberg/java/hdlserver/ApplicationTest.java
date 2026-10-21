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

class ApplicationTest {

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.255", "fd12:3456:789a::1", "fc00::1"})
    void localAddresses(String address) {
        assertTrue(Application.isLocalAddress(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "127.0.0.1", "::1", "2001:4860:4860::8888", "", "localhost", "example.com", "1.2.3.4:5678"})
    void nonLocalAddresses(String address) {
        assertFalse(Application.isLocalAddress(address));
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

        assertEquals(expected, Application.isLocalRequest(remoteAddress, headerMap::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.199:4567", "192.168.1.199", "raspberrypi:4567", "localhost:4567", "rpi2.local:4567", "server.lan", "server.home.arpa:4567", "[fd00::1]:4567", "RaspberryPi.Local.:4567"})
    void allowedHostNames(String host) {
        assertTrue(Application.isAllowedHost(host, Collections.emptySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example.com:4567", "192.168.1.199.attacker.example", "evil.com", ""})
    void hostNamesThatCouldBeDnsRebinding(String host) {
        assertFalse(Application.isAllowedHost(host, Collections.emptySet()));
    }

    @Test
    void hostNamesCanBeAllowed() {
        Set<String> allowedHosts = Application.parseAllowedHosts(" hdl.example.com, Other.Example.com ,");

        assertTrue(Application.isAllowedHost("hdl.example.com:4567", allowedHosts));
        assertTrue(Application.isAllowedHost("other.example.com", allowedHosts));
        assertFalse(Application.isAllowedHost("another.example.com", allowedHosts));
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
        assertEquals(expected, Application.isSameOrigin(originOrReferer, host));
    }

    @Test
    void requestsWithoutOriginOrRefererAreNotSameOrigin() {
        assertFalse(Application.isSameOrigin(null, "192.168.1.199:4567"));
    }

    @Test
    void authToken() {
        assertTrue(Application.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(Application.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXY", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(Application.isValidAuthToken("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(Application.isValidAuthToken("", ""));
        assertFalse(Application.isValidAuthToken(null, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(Application.isValidAuthToken(12345L, "12345"));
        assertFalse(Application.isValidAuthToken("ABCDEFGHIJKLMNOPQRSTUVWXYZ", null));
    }

    @Test
    void percent() {
        assertEquals(30, Application.parsePercent(30L));
        assertEquals(31, Application.parsePercent(30.6));
        assertEquals(40, Application.parsePercent("40"));
        assertEquals(40, Application.parsePercent(" 40 % "));
        assertEquals(100, Application.parsePercent(250L));
        assertEquals(0, Application.parsePercent(-1L));
        assertThrows(HaltException.class, () -> Application.parsePercent(null));
        assertThrows(HaltException.class, () -> Application.parsePercent("bright"));
        assertThrows(HaltException.class, () -> Application.parsePercent(true));
    }

    @Test
    void apiPaths() {
        assertTrue(Application.isApiPath("/api/actions/1"));
        assertFalse(Application.isApiPath("/apiary"));
        assertFalse(Application.isApiPath("/settings"));
        assertFalse(Application.isApiPath(null));
    }

}
