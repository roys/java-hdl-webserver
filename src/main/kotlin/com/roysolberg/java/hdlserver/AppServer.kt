package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.Javalin
import io.javalin.http.Context
import org.apache.logging.log4j.LogManager
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.io.ssl.SslConnection
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnectionLimit
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The port the Android app talks to. It's HTTPS only, with Busbridge's own [AppCertificate], and has only the app's
 * routes, so the pages and the API can't be reached through it. Like the pages, it only answers local and VPN addresses.
 *
 * @param serverId stays the same for this server, so the app can tell it's the one it knows
 * @param version this server's version, or null for a development build
 * @param pairing where the app asks to pair, which the user allows on Settings
 * @param relay carries the bus to paired phones
 * @param isAllowedAddress whether a connection from this address may be answered, the socket's own address
 */
class AppServer @JvmOverloads constructor(
    private val certificate: AppCertificate,
    private val serverId: String,
    private val version: String?,
    private val pairing: Pairing,
    private val relay: Relay,
    private val isAllowedAddress: (String) -> Boolean = HdlServer::isLocalAddress,
) : AutoCloseable {

    private val objectMapper = ObjectMapper()
    private var javalin: Javalin? = null

    /**
     * @param port the port to listen on, or 0 for any free one
     * @throws io.javalin.util.JavalinException if it can't listen there, e.g. because something else already does
     */
    @Synchronized
    fun start(port: Int): AppServer {
        check(javalin == null) { "The app port is already open" }
        // Jetty only needs a password for the key in memory, where it's never written down
        val password = Base64.getEncoder().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        val sslContextFactory = SslContextFactory.Server().apply {
            keyStore = certificate.keyStore(password.toCharArray())
            keyStorePassword = password
            setIncludeProtocols("TLSv1.3", "TLSv1.2")
            addExcludeCipherSuites("^.*_CBC_.*$") // Only ciphers that also authenticate, which every Android version has
        }
        val app = Javalin.create { config ->
            config.startup.showJavalinBanner = false
            config.startup.showOldJavalinVersionWarning = false
            config.http.maxRequestSize = MAX_REQUEST_BYTES
            config.contextResolver.ip = { ctx -> ctx.req().remoteAddr } // Never a header: there's no proxy in front of this
            // A few phones at home need little, and someone flooding the port can't take more from the computer
            config.jetty.threadPool = QueuedThreadPool(MAX_THREADS, MIN_THREADS).apply { name = "app-port" }
            config.jetty.modifyWebSocketServletFactory { factory ->
                factory.maxBinaryMessageSize = MAX_PACKET_BYTES
                factory.maxTextMessageSize = MAX_REQUEST_BYTES
                factory.idleTimeout = RELAY_IDLE_TIMEOUT // Kept alive by the pings
            }
            config.jetty.addConnector { server, httpConfiguration ->
                val httpsConfiguration = HttpConfiguration(httpConfiguration).apply {
                    sendServerVersion = false
                    // Phones reach Busbridge by addresses and names the certificate can't list, and pin it instead
                    addCustomizer(SecureRequestCustomizer().apply { isSniHostCheck = false })
                }
                ServerConnector(
                    server,
                    SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    HttpConnectionFactory(httpsConfiguration),
                ).apply {
                    this.port = port
                    idleTimeout = IDLE_TIMEOUT_MILLIS
                    server.addBean(NetworkConnectionLimit(MAX_CONNECTIONS, this))
                    addBean(AddressLimit(isAllowedAddress))
                }
            }

            config.routes.exception(HaltException::class.java) { e, ctx -> ctx.status(e.status).result(e.body) }
            config.routes.before { ctx ->
                // Answers are JSON for the app, never something a browser should show, run, keep or frame
                ctx.header("X-Content-Type-Options", "nosniff")
                ctx.header("Cache-Control", "no-store")
                ctx.header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                ctx.header("Referrer-Policy", "no-referrer")
                ctx.header("Cross-Origin-Resource-Policy", "same-origin")
                refuseOthers(ctx)
            }
            // The relay's key is checked before the upgrade, so a wrong one gets a plain 401
            config.routes.wsBeforeUpgrade(RELAY_PATH) { ctx ->
                refuseOthers(ctx)
                val key = ctx.header("Authorization")?.takeIf { it.startsWith(BEARER) }?.let { Pairing.decode(it.removePrefix(BEARER), Pairing.KEY_BYTES) }
                val phone = key?.let(relay::authenticate)
                if (phone == null) {
                    logger.info("Refused a relay connection without a paired phone's key, from [{}].", ctx.ip())
                    ctx.header("WWW-Authenticate", "Bearer")
                    throw HaltException(401, "Not a paired phone.")
                }
                ctx.attribute(PHONE_ATTRIBUTE, phone)
            }
            config.routes.ws(RELAY_PATH) { ws ->
                ws.onConnect { ctx -> relay.connected(ctx, checkNotNull(ctx.attribute<Phones.Phone>(PHONE_ATTRIBUTE))) }
                ws.onBinaryMessage(relay::received)
                ws.onClose(relay::closed)
            }
            config.routes.get(INFO_PATH) { ctx ->
                ctx.json(mapOf("name" to NAME, "version" to (version ?: "development"), "serverId" to serverId))
            }
            // Pairing, see Pairing. What's asked for and answered here is never logged: it holds the key.
            config.routes.post(PAIR_PATH) { ctx ->
                val json = readJson(ctx)
                val name = json.text("name")?.let(Pairing::validName)
                val commitment = Pairing.decode(json.text("commitment"), Pairing.NONCE_BYTES)
                if (name == null || commitment == null) {
                    throw badRequest(ctx)
                }
                when (val asked = pairing.ask(name, commitment)) {
                    is Pairing.Asked.Created -> ctx.json(mapOf("requestId" to asked.requestId, "nonce" to Pairing.base64(asked.nonce)))
                    Pairing.Asked.Closed -> ctx.status(409).json(mapOf("status" to "closed"))
                    Pairing.Asked.Full -> ctx.status(409).json(mapOf("status" to "full"))
                }
            }
            config.routes.post(PAIR_REVEAL_PATH) { ctx ->
                val (requestId, nonce) = readRequest(ctx)
                ctx.json(mapOf("status" to pairing.reveal(requestId, nonce).json))
            }
            config.routes.post(PAIR_POLL_PATH) { ctx ->
                val (requestId, nonce) = readRequest(ctx)
                val polled = pairing.poll(requestId, nonce)
                val key = polled.key
                ctx.json(
                    if (key != null) {
                        mapOf("status" to polled.status.json, "key" to Pairing.base64(key), "serverId" to serverId)
                    } else {
                        mapOf("status" to polled.status.json)
                    }
                )
            }
        }
        try {
            app.start()
        } catch (e: RuntimeException) {
            app.stop() // Nothing half open is left behind
            throw e
        }
        javalin = app
        logger.info("The app port is open on {}, with the certificate {}.", app.port(), certificate.fingerprint)
        return this
    }

    /** Only phones at home or on the VPN, and never web pages. */
    private fun refuseOthers(ctx: Context) {
        if (!isAllowedAddress(ctx.ip())) {
            logger.info("Refused a request to the app port from [{}].", ctx.ip())
            throw HaltException(403, "Only for the local network.")
        }
        // The app never sends one, while a browser does for a web page's requests to other sites
        if (ctx.header("Origin") != null) {
            logger.info("Refused a request to the app port from a web page, from [{}].", ctx.ip())
            throw HaltException(403, "Not for web pages.")
        }
    }

    private fun readJson(ctx: Context): JsonNode {
        val json = try {
            objectMapper.readTree(ctx.body())
        } catch (e: JacksonException) {
            null
        }
        return json?.takeIf { it.isObject } ?: throw badRequest(ctx)
    }

    /** The requestId and the phone's revealed nonce that the reveal and the poll send. */
    private fun readRequest(ctx: Context): Pair<String, ByteArray> {
        val json = readJson(ctx)
        val requestId = json.text("requestId")?.takeIf { Pairing.decode(it, Pairing.REQUEST_ID_BYTES) != null }
        val nonce = Pairing.decode(json.text("nonce"), Pairing.NONCE_BYTES)
        if (requestId == null || nonce == null) {
            throw badRequest(ctx)
        }
        return requestId to nonce
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    private fun badRequest(ctx: Context): HaltException {
        logger.info("Got a request to {} on the app port that isn't valid, from [{}].", ctx.path(), ctx.ip())
        return HaltException(400, "Bad request.")
    }

    @Synchronized
    fun port(): Int = checkNotNull(javalin) { "The app port isn't open" }.port()

    @Synchronized
    override fun close() {
        javalin?.let {
            it.stop()
            logger.info("The app port is closed.")
        }
        javalin = null
    }

    /**
     * Closes a connection from an address that isn't allowed before anything is said on it, and keeps one address from
     * taking all the connections the port has, e.g. by opening many and saying little on each.
     */
    private class AddressLimit(private val isAllowedAddress: (String) -> Boolean) : Connection.Listener {
        private val connectionsPerAddress = ConcurrentHashMap<InetAddress, Int>()
        /** Where each counted connection is from, since a closed one no longer knows. */
        private val counted = ConcurrentHashMap<Connection, InetAddress>()

        override fun onOpened(connection: Connection) {
            // Each connection starts encrypted, and the HTTP connection inside it is told too: one per connection
            if (connection !is SslConnection) return
            val address = (connection.endPoint.remoteSocketAddress as? InetSocketAddress)?.address
            if (address == null || !isAllowedAddress(address.hostAddress)) {
                logger.info("Refused a connection to the app port from [{}].", address?.hostAddress)
                connection.endPoint.close()
                return
            }
            var isLetIn = false
            connectionsPerAddress.compute(address) { _, count ->
                val current = count ?: 0
                if (current < MAX_CONNECTIONS_PER_ADDRESS) {
                    isLetIn = true
                    current + 1
                } else {
                    current
                }
            }
            if (!isLetIn) {
                logger.info("Refused more than {} connections to the app port from [{}].", MAX_CONNECTIONS_PER_ADDRESS, address.hostAddress)
                connection.endPoint.close()
                return
            }
            counted[connection] = address
        }

        override fun onClosed(connection: Connection) {
            val address = counted.remove(connection) ?: return
            connectionsPerAddress.computeIfPresent(address) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    companion object {
        const val DEFAULT_PORT = 4568

        /** The app port, if not [DEFAULT_PORT]. */
        const val PORT_PROPERTY = "hdlserver.appPort"
        const val INFO_PATH = "/relay/info"
        const val PAIR_PATH = "/relay/pair"
        const val PAIR_REVEAL_PATH = "/relay/pair/reveal"
        const val PAIR_POLL_PATH = "/relay/pair/poll"
        const val RELAY_PATH = "/relay"
        private const val BEARER = "Bearer "
        private const val PHONE_ATTRIBUTE = "phone"
        /** The largest UDP packet, as the bus code takes. */
        private const val MAX_PACKET_BYTES = 65_535L
        private val RELAY_IDLE_TIMEOUT = Duration.ofSeconds(75)
        const val NAME = "Busbridge"
        private const val MAX_REQUEST_BYTES = 16 * 1024L
        private const val IDLE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_THREADS = 32
        private const val MIN_THREADS = 2
        private const val MAX_CONNECTIONS = 50
        private const val MAX_CONNECTIONS_PER_ADDRESS = 8
        private val logger = LogManager.getLogger(AppServer::class.java)
    }
}
