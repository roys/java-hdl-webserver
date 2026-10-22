package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusNetworkTest {

    private static final BusNetwork WIRED = network("eth0", "10.0.0.5", "10.0.0.255");
    private static final BusNetwork WIRELESS = network("wlan0", "10.0.1.5", "10.0.1.255");
    private static final BusNetwork CONTAINERS = network("docker0", "172.17.0.1", "172.17.255.255");
    private static final BusNetwork VPN = network("eth1", "100.64.0.1", "100.127.255.255");
    private static final BusNetwork PUBLIC = network("eth2", "192.0.2.10", "192.0.2.255");

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
    void theLimitedBroadcastIsAllOnes() {
        assertEquals("255.255.255.255", BusNetwork.LIMITED_BROADCAST.getHostAddress());
    }

    private static BusNetwork network(String interfaceName, String address, String broadcast) {
        return new BusNetwork(interfaceName, ipv4(address), ipv4(broadcast));
    }

    private static Inet4Address ipv4(String address) {
        try {
            return (Inet4Address) InetAddress.getByName(address); // A literal, so no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
