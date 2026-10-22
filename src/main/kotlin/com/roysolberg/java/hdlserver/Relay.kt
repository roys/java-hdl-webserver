package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.websocket.WsBinaryMessageContext
import io.javalin.websocket.WsCloseContext
import io.javalin.websocket.WsConnectContext
import io.javalin.websocket.WsContext
import org.apache.logging.log4j.LogManager
import org.eclipse.jetty.websocket.api.Callback
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.InstantSource
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries the bus to paired phones away from home and back, over a WebSocket on the app port. Binary messages are whole
 * HDL UDP packets, as the app sends and reads them at home.
 *
 * @param addresses where the app can reach Busbridge, sent to it on every connection
 */
class Relay @JvmOverloads constructor(
    private val serverId: String,
    private val version: String?,
    private val phones: Phones,
    private val pairing: Pairing,
    private val bus: Bus,
    private val activityLog: ActivityLog,
    private val addresses: () -> List<String>,
    private val clock: InstantSource = InstantSource.system(),
) : AutoCloseable {

    /** The bus, as the relay sees it. */
    interface Bus {
        /** This server's address on the bus network, which goes in every packet sent, or null without one. */
        fun address(): InetAddress?

        fun broadcast(packet: ByteArray)
    }

    private class Connection(val phone: Phones.Phone, val ws: WsContext, val from: String?) {
        /** Packets from the phone waiting to go to the bus, a few at a time. */
        val toBus = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_PACKETS)
        /** Bytes waiting to go to the phone. */
        val toPhoneBytes = AtomicLong()
        val sentToBus = AtomicLong()
        @Volatile
        var isDropping = false
    }

    private val objectMapper = ObjectMapper()
    private val connections = ConcurrentHashMap<String, Connection>()
    private val sender = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "relay-sender").apply { isDaemon = true } }

    init {
        sender.scheduleAtFixedRate(::sendToBus, SEND_INTERVAL_MILLIS, SEND_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
    }

    val isFull: Boolean
        get() = connections.size >= MAX_CONNECTIONS

    /**
     * The phone with this key. The first time an allowed phone connects, this completes its pairing.
     *
     * @return null for a key no phone has
     */
    fun authenticate(key: ByteArray): Phones.Phone? = phones.find(key)
        ?: pairing.complete(key) { name -> phones.add(name, key, clock.instant()) }?.also { phone ->
            activityLog.record(source(phone, null), "Pairing", "Paired", succeeded = true)
        }
        // Two first connections at once: the one that didn't complete it finds the phone the other saved
        ?: phones.find(key)

    @Synchronized
    fun connected(ws: WsConnectContext, phone: Phones.Phone) {
        val from = (ws.session.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
        if (!phones.contains(phone.id)) {
            ws.closeSession(REMOVED, "Removed from Busbridge") // While it was connecting
            return
        }
        if (connections.size >= MAX_CONNECTIONS && !connections.containsKey(phone.id)) {
            ws.closeSession(TRY_AGAIN_LATER, "Too many phones connected")
            return
        }
        // A phone has one connection; a new one means the old is gone, even if it hasn't been noticed yet
        connections.put(phone.id, Connection(phone, ws, from))?.ws?.closeSession(NORMAL, "Replaced by a newer connection")
        ws.enableAutomaticPings(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        ws.session.sendText(
            objectMapper.writeValueAsString(mapOf("version" to (version ?: "development"), "serverId" to serverId, "addresses" to addresses())),
            Callback.NOOP,
        )
        phones.seen(phone.id, clock.instant())
        activityLog.record(source(phone, from), "Relay", "Connected", succeeded = true)
        logger.info("The phone calling itself [{}] connected to the relay from [{}].", phone.name, from)
    }

    /** A packet from a phone, which is checked and sent on to the bus in turn. */
    fun received(ws: WsBinaryMessageContext) {
        val connection = connections.values.firstOrNull { it.ws == ws } ?: return
        val data = ws.data()
        val packet = ByteArray(data.remaining()).also(data::get)
        val validity = HdlFrame.validate(packet)
        if (validity != HdlFrame.Validity.VALID) {
            logger.info("Dropped a packet ({}) from the phone calling itself [{}].", validity, connection.phone.name)
            return
        }
        if (connection.toBus.offer(packet.copyOf(HdlFrame.packetLength(packet)))) {
            connection.isDropping = false
        } else if (!connection.isDropping) {
            connection.isDropping = true
            logger.warn("The phone calling itself [{}] sends faster than the bus takes. Dropping until there's room.", connection.phone.name)
        }
    }

    @Synchronized
    fun closed(ws: WsCloseContext) {
        ws.disableAutomaticPings()
        val connection = connections.values.firstOrNull { it.ws == ws } ?: return // Replaced or removed already
        connections.remove(connection.phone.id, connection)
        phones.seen(connection.phone.id, clock.instant())
        activityLog.record(source(connection.phone, connection.from), "Relay", "Disconnected after ${connection.sentToBus.get()} packets to the bus", succeeded = true)
        logger.info("The phone calling itself [{}] disconnected from the relay ({} {}).", connection.phone.name, ws.status(), ws.reason())
    }

    /** A packet heard on the bus, which goes to every connected phone. */
    fun forward(packet: ByteArray) {
        for (connection in connections.values) {
            val size = packet.size.toLong()
            if (connection.toPhoneBytes.addAndGet(size) > MAX_BYTES_TO_PHONE) {
                // It isn't reading, so it would only get further behind. It reconnects and reads what it needs again.
                logger.warn("The phone calling itself [{}] doesn't keep up with the bus, so it's disconnected.", connection.phone.name)
                connection.ws.session.disconnect()
                continue
            }
            val done = Runnable { connection.toPhoneBytes.addAndGet(-size) }
            connection.ws.session.sendBinary(ByteBuffer.wrap(packet), Callback.from(done) { done.run() })
        }
    }

    /** Disconnects a removed phone, which the app shows as removed. */
    @Synchronized
    fun disconnect(phoneId: String) {
        connections.remove(phoneId)?.ws?.closeSession(REMOVED, "Removed from Busbridge")
    }

    override fun close() {
        sender.shutdownNow()
    }

    /** One packet from each phone per round, which limits each phone to what the bus can take. */
    private fun sendToBus() {
        try {
            for (connection in connections.values) {
                val packet = connection.toBus.poll() ?: continue
                bus.address()?.address?.copyInto(packet) // Bus devices answer to the address in the packet
                bus.broadcast(packet)
                connection.sentToBus.incrementAndGet()
            }
        } catch (e: RuntimeException) {
            // Thrown out of here, it would stop all later rounds
            logger.error("Couldn't send a packet from the relay to the bus.", e)
        }
    }

    private fun source(phone: Phones.Phone, from: String?) = "Phone \"${phone.name}\"" + (from?.let { " ($it)" } ?: "")

    companion object {
        const val REMOVED = 4401
        private const val NORMAL = 1000
        private const val TRY_AGAIN_LATER = 1013
        private const val MAX_CONNECTIONS = 5
        private const val MAX_QUEUED_PACKETS = 200
        /** 20 packets a second from each phone. */
        private const val SEND_INTERVAL_MILLIS = 50L
        private const val PING_INTERVAL_SECONDS = 30L
        private const val MAX_BYTES_TO_PHONE = 1024 * 1024L
        private val logger = LogManager.getLogger(Relay::class.java)
    }
}
