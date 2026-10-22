package com.roysolberg.java.hdlserver

import io.javalin.Javalin
import org.apache.logging.log4j.LogManager
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnectionLimit
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.security.SecureRandom
import java.util.Base64

/**
 * The port the Android app talks to. It's HTTPS only, with Busbridge's own [AppCertificate], and has only the app's
 * routes, so the pages and the API can't be reached through it. Like the pages, it only answers local and VPN addresses.
 *
 * @param serverId stays the same for this server, so the app can tell it's the one it knows
 * @param version this server's version, or null for a development build
 * @param isAllowedAddress whether a connection from this address may be answered, the socket's own address
 */
class AppServer @JvmOverloads constructor(
    private val certificate: AppCertificate,
    private val serverId: String,
    private val version: String?,
    private val isAllowedAddress: (String) -> Boolean = HdlServer::isLocalAddress,
) : AutoCloseable {

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
        }
        val app = Javalin.create { config ->
            config.startup.showJavalinBanner = false
            config.startup.showOldJavalinVersionWarning = false
            config.http.maxRequestSize = MAX_REQUEST_BYTES
            config.contextResolver.ip = { ctx -> ctx.req().remoteAddr } // Never a header: there's no proxy in front of this
            // A few phones at home need little, and someone flooding the port can't take more from the computer
            config.jetty.threadPool = QueuedThreadPool(MAX_THREADS, MIN_THREADS).apply { name = "app-port" }
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
            config.routes.get(INFO_PATH) { ctx ->
                ctx.json(mapOf("name" to NAME, "version" to (version ?: "development"), "serverId" to serverId))
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

    companion object {
        const val DEFAULT_PORT = 4568

        /** The app port, if not [DEFAULT_PORT]. */
        const val PORT_PROPERTY = "hdlserver.appPort"
        const val INFO_PATH = "/relay/info"
        const val NAME = "Busbridge"
        private const val MAX_REQUEST_BYTES = 16 * 1024L
        private const val IDLE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_THREADS = 32
        private const val MIN_THREADS = 2
        private const val MAX_CONNECTIONS = 50
        private val logger = LogManager.getLogger(AppServer::class.java)
    }
}
