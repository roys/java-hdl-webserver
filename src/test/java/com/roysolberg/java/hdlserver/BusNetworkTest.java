package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusNetworkTest {

    private static final BusNetwork WIRED = network("eth0", "10.0.0.5", "10.0.0.255", 24);
    private static final BusNetwork WIRELESS = network("wlan0", "10.0.1.5", "10.0.1.255", 24);
    private static final BusNetwork CONTAINERS = network("docker0", "172.17.0.1", "172.17.255.255", 16);
    private static final BusNetwork VPN = network("eth1", "100.64.0.1", "100.127.255.255", 10);
    private static final BusNetwork PUBLIC = network("eth2", "192.0.2.10", "192.0.2.255", 24);

    @Test
    void theHomeNetworkIsPickedOverContainersAndVpns() {
        assertEquals(WIRED, BusNetwork.choose(List.of(CONTAINERS, VPN, WIRED)));
        assertEquals(WIRELESS, BusNetwork.choose(List.of(CONTAINERS, WIRELESS)));
    }

    @Test
    void aPrivateAddressIsPickedOverAPublicOne() {
        assertEquals(WIRED, BusNetwork.choose(List.of(PUBLIC, WIRED)));
    }

    @Test
    void theFirstOfEquallyLikelyNetworksIsPicked() {
        assertEquals(WIRELESS, BusNetwork.choose(List.of(WIRELESS, WIRED)));
    }

    @Test
    void containersAreBetterThanNothingButAVpnIsNot() {
        assertEquals(CONTAINERS, BusNetwork.choose(List.of(CONTAINERS)));
        assertNull(BusNetwork.choose(List.of(VPN)));
        assertNull(BusNetwork.choose(List.of()));
    }

    @Test
    void theNetworkADeviceWasHeardOnIsPicked() {
        assertEquals(WIRED, BusNetwork.choose(List.of(WIRELESS, WIRED), ipv4("10.0.0.40")));
        assertEquals(WIRELESS, BusNetwork.choose(List.of(WIRELESS, WIRED), ipv4("10.0.1.40")));
        assertEquals(WIRELESS, BusNetwork.choose(List.of(WIRELESS, WIRED), ipv4("198.51.100.7")), "Heard from elsewhere");
    }

    @Test
    void anAddressIsOnANetworkByItsPrefix() {
        assertTrue(WIRED.contains(ipv4("10.0.0.255")));
        assertFalse(WIRED.contains(ipv4("10.0.1.1")));
        assertTrue(VPN.contains(ipv4("100.127.0.1")));
        assertFalse(VPN.contains(ipv4("100.128.0.1")));
        assertTrue(network("eth0", "10.0.0.5", "10.0.0.5", 32).contains(ipv4("10.0.0.5")));
        assertTrue(network("eth0", "10.0.0.5", "255.255.255.255", 0).contains(ipv4("198.51.100.7")));
    }

    @Test
    void theLimitedBroadcastIsAllOnes() {
        assertEquals("255.255.255.255", BusNetwork.LIMITED_BROADCAST.getHostAddress());
    }

    private static BusNetwork network(String interfaceName, String address, String broadcast, int prefixLength) {
        return new BusNetwork(interfaceName, ipv4(address), ipv4(broadcast), prefixLength);
    }

    private static Inet4Address ipv4(String address) {
        try {
            return (Inet4Address) InetAddress.getByName(address); // A literal, so no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
