package com.roysolberg.java.hdlserver

import org.apache.logging.log4j.LogManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The network the HDL bus is on: this server's address there, which goes in every packet it sends, and the address that
 * reaches every device on it. Found from the computer's own network interfaces, so it stays the home network when a VPN
 * like Tailscale carries the rest of the traffic, and nothing is asked of the Internet.
 */
data class BusNetwork(val interfaceName: String, val address: Inet4Address, val broadcast: Inet4Address, val prefixLength: Int) {

    /** Whether [other] is an address on this network. */
    fun contains(other: InetAddress): Boolean {
        if (other !is Inet4Address) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return address.toInt() and mask == other.toInt() and mask
    }

    override fun toString() = "$interfaceName (${address.hostAddress}/$prefixLength, broadcast ${broadcast.hostAddress})"

    companion object {
        /** 255.255.255.255, which reaches the devices on whichever network the computer picks. */
        @JvmField
        val LIMITED_BROADCAST = Inet4Address.getByAddress(byteArrayOf(-1, -1, -1, -1)) as Inet4Address

        private val WIRED_OR_WIRELESS = Regex("(eth|en|wlan|wl).*")
        private val logger = LogManager.getLogger(BusNetwork::class.java)

        /**
         * @param heardFrom where a device on the bus was heard from, if one has been
         * @return the likeliest bus network, or null if no network interface has an IPv4 broadcast address
         */
        @JvmStatic
        @JvmOverloads
        fun find(heardFrom: InetAddress? = null): BusNetwork? = choose(candidates(), heardFrom)

        /**
         * Prefers the network a device on the bus was heard on, then wired and wireless interfaces, then private
         * addresses. VPN addresses never reach the bus, and neither do the bridges that containers and virtual machines
         * get (docker0, br-…, virbr0).
         */
        @JvmStatic
        @JvmOverloads
        fun choose(networks: List<BusNetwork>, heardFrom: InetAddress? = null): BusNetwork? = networks
            .filterNot { isVpnAddress(it.address) }
            .sortedWith(
                // Of networks that overlap, like a /8 and a /24 in it, the device is on the narrowest
                compareByDescending<BusNetwork> { if (heardFrom != null && it.contains(heardFrom)) it.prefixLength else -1 }
                    .thenByDescending { WIRED_OR_WIRELESS.matches(it.interfaceName) }
                    .thenByDescending { it.address.isSiteLocalAddress }
            )
            .firstOrNull()

        private fun candidates(): List<BusNetwork> = try {
            NetworkInterface.networkInterfaces().toList()
                .filter(::canReachTheBus)
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val address = interfaceAddress.address as? Inet4Address
                        val broadcast = interfaceAddress.broadcast as? Inet4Address
                        if (address != null && broadcast != null) {
                        BusNetwork(networkInterface.name, address, broadcast, interfaceAddress.networkPrefixLength.toInt())
                    } else {
                        null
                    }
                    }
                }
        } catch (e: SocketException) {
            logger.warn("Couldn't list the network interfaces.", e)
            emptyList()
        }

        /** VPN interfaces, like Tailscale's and WireGuard's, are point-to-point, so a broadcast on them reaches no bus. */
        private fun canReachTheBus(networkInterface: NetworkInterface): Boolean = try {
            networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isPointToPoint && !networkInterface.isVirtual
        } catch (e: SocketException) {
            false // Gone while being looked at
        }

        private fun Inet4Address.toInt(): Int = address.fold(0) { value, byte -> value shl 8 or (byte.toInt() and 0xFF) }

        /** 100.64.0.0/10, where Tailscale gives out addresses. */
        private fun isVpnAddress(address: Inet4Address): Boolean {
            val bytes = address.address
            return bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64
        }
    }
}
