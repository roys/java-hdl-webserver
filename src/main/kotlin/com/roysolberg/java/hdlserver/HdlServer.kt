package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.roysolberg.java.hdlserver.hdl.Action
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent
import com.roysolberg.java.hdlserver.service.HdlService
import com.roysolberg.java.hdlserver.util.HdlUtil
import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.staticfiles.Location
import org.apache.logging.log4j.LogManager
import org.mapdb.DB
import org.mapdb.DBMaker
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.RejectedExecutionException

/**
 * The web server: the API that IFTTT applets call, and the web pages for the local network.
 */
class HdlServer @JvmOverloads constructor(
    private val database: DB,
    private val hdlService: HdlService?,
    /** The database's file, which must be readable by its owner only before the app port's private key goes in it. */
    private val databaseFile: Path? = null,
) {

    /** Why the app port isn't open although Settings says it should be. */
    enum class AppPortProblem {
        /** Something else listens on the port. */
        PORT_TAKEN,

        /** Others than the database's owner can read it, or that can't be checked (on Windows). */
        DATABASE_NOT_PRIVATE,
    }


    @JvmField
    internal val configConcurrentMap: ConcurrentMap<String, String> = database.openMap("config")

    @JvmField
    internal val componentsConcurrentMap: ConcurrentMap<String, HdlComponent> = database.openMap("components")

    @JvmField
    internal val actionsConcurrentMap: ConcurrentMap<Int, Action> = database.openMap("actions")

    @JvmField
    internal val actionAliasesConcurrentMap: ConcurrentMap<String, Int> = database.openMap("actionAliases")

    private val allowedHosts = parseAllowedHosts(System.getProperty(ALLOWED_HOSTS_PROPERTY))
    private val objectMapper = ObjectMapper()
    private val templateEngine = TemplateEngine.createPrecompiled(ContentType.Html)
    private val startedAt = Instant.now()
    private var javalin: Javalin? = null
    private var livePages: LivePages? = null
    private var updateCheck: UpdateCheck? = null
    private var appPort = AppServer.DEFAULT_PORT
    // Changed under the lock in updateAppPort(), and read by the Settings page
    @Volatile
    private var appServer: AppServer? = null

    @Volatile
    private var appCertificate: AppCertificate? = null

    @Volatile
    private var appPortProblem: AppPortProblem? = null

    /** Pairing phones on the app port, while it's open. */
    @Volatile
    private var pairing: Pairing? = null

    /** Carries the bus to paired phones, while the app port is open. */
    @Volatile
    private var relay: Relay? = null

    private val phones = Phones(database.openMap("phones"), database::commit)

    private val isCheckingForUpdates: Boolean
        get() = configConcurrentMap[CHECK_FOR_UPDATES_KEY].toBoolean()

    private val isAppPortOn: Boolean
        get() = configConcurrentMap[APP_PORT_ON_KEY].toBoolean()

    @JvmField
    internal val activityLog = ActivityLog()
    private val apiCallsToday = DailyCounter()

    init {
        if (!configConcurrentMap.containsKey(AUTH_TOKEN_KEY)) {
            configConcurrentMap[AUTH_TOKEN_KEY] = generateAuthToken()
            database.commit()
        }
    }

    /**
     * @param appPort the port the Android app connects to, when that's turned on in Settings
     */
    @JvmOverloads
    fun start(port: Int, appPort: Int = AppServer.DEFAULT_PORT): HdlServer {
        this.appPort = appPort
        val hdlService = checkNotNull(hdlService) { "The server can't start without an HdlService" }
        val livePages = LivePages { template -> renderLive(template, hdlService) }.also { livePages = it }
        val updateCheck = UpdateCheck(VERSION) { isCheckingForUpdates }.also { updateCheck = it }
        javalin = Javalin.create { config ->
            config.startup.showJavalinBanner = false
            config.startup.showOldJavalinVersionWarning = false
            // Always the socket's own address: X-Forwarded-For is something anyone can send to look local
            config.contextResolver.ip = { ctx -> ctx.req().remoteAddr }
            config.contextResolver.host = { ctx -> ctx.req().getHeader("Host") }
            config.jetty.modifyHttpConfiguration { httpConfiguration -> httpConfiguration.sendServerVersion = false }
            // The pages' CSS, scripts and icons. The before-handlers below make them local-only, like the pages.
            config.staticFiles.add { staticFiles ->
                staticFiles.hostedPath = ASSETS_PATH
                staticFiles.directory = "/public"
                staticFiles.location = Location.CLASSPATH
                staticFiles.mimeTypes.add("application/manifest+json", "webmanifest")
            }

            config.routes.exception(HaltException::class.java) { e, ctx -> ctx.status(e.status).result(e.body) }
            config.routes.before { ctx ->
                logger.info("Request: {} - \"{} {} {}\" - \"{}\"", ctx.ip(), ctx.req().method, ctx.path(), ctx.req().protocol, ctx.userAgent())
                // Every response, also errors and the API's: not to be guessed at, loaded by other sites, or stored.
                // Pages hold the API key, so only the pages' CSS, scripts and icons may be cached.
                ctx.header("X-Content-Type-Options", "nosniff")
                ctx.header("Cross-Origin-Resource-Policy", "same-origin")
                if (!ctx.path().startsWith("$ASSETS_PATH/")) {
                    ctx.header("Cache-Control", "no-store")
                }
                // Javalin would otherwise route by this header instead of the request's own method
                if (ctx.header(METHOD_OVERRIDE_HEADER) != null) {
                    logger.info("Got {} from [{}].", METHOD_OVERRIDE_HEADER, ctx.ip())
                    throw HaltException(400, "Bad request.")
                }
            }
            // Everything but the API is the web front end, which is only for the local network.
            config.routes.before { ctx ->
                if (!isApiPath(ctx.normalizedPath())) {
                    requireLocalRequest(ctx)
                    requireAllowedHost(ctx)
                    if (ctx.req().method !in SAFE_METHODS) {
                        requireSameOrigin(ctx)
                    }
                    ctx.header("X-Frame-Options", "DENY")
                    ctx.header("Referrer-Policy", "strict-origin-when-cross-origin") // YouTube embeds need the origin
                    ctx.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                    ctx.header("Permissions-Policy", PERMISSIONS_POLICY)
                    ctx.header("Cross-Origin-Opener-Policy", "same-origin")
                }
            }

            for (page in listOf("actions", "security", "demo")) {
                config.routes.get("/$page") { ctx -> render(ctx, page) }
            }
            config.routes.get("/settings") { ctx ->
                render(
                    ctx, "settings",
                    "checkForUpdates" to isCheckingForUpdates,
                    "appPortOn" to isAppPortOn,
                    "appPort" to appPort,
                    "appFingerprint" to appCertificate?.takeIf { appServer != null }?.fingerprint,
                    "appPortProblem" to appPortProblem,
                    *phonesParams().toList().toTypedArray(),
                )
            }
            config.routes.get("/") { ctx ->
                val heard = hdlService.heardComponentIds
                render(ctx, "index", "heard" to heard, "conflicts" to sharedAddresses(componentsConcurrentMap.values, heard))
            }
            config.routes.get("/activity") { ctx -> render(ctx, "activity", "entries" to activityLog.entries()) }
            config.routes.get("/status") { ctx -> render(ctx, "status", "status" to status(hdlService)) }
            // Live updates for open pages. A browser only says where a request comes from (Origin) when it's another
            // site that asks, so that's refused, as is opening more than a handful.
            for (path in listOf("/status/events", "/activity/events", "/settings/events")) {
                config.routes.before(path) { ctx ->
                    ctx.header("Origin")?.let { origin ->
                        if (!isSameOrigin(origin, ctx.host())) {
                            throw HaltException(403, "Forbidden.")
                        }
                    }
                    if (livePages.isFull) {
                        throw HaltException(503, "Too many open pages.")
                    }
                }
            }
            // The check above answers most with a clear 503; this one also holds when several pages open at once
            config.routes.sse("/status/events") { client -> livePages.add(client, "live/status") }
            config.routes.sse("/activity/events") { client -> livePages.add(client, "live/activity") }
            config.routes.sse("/settings/events") { client -> livePages.add(client, "live/phones") }
            config.routes.post("/run_action") { ctx ->
                val (message, succeeded) = runAction(hdlService, ctx.formParam("actionId"), ctx.formParam("value"), "Web page (${ctx.ip()})")
                ctx.redirectWithMessage("/actions", message.takeIf { succeeded }, message.takeUnless { succeeded })
            }
            config.routes.post("/create_custom_action") { ctx ->
                val errorMessage = createCustomAction(
                    ctx.formParam("description"), ctx.formParams("components[]"), ctx.formParams("operations[]"),
                    ctx.formParams("parameters1[]"), ctx.formParams("parameters2[]"), ctx.formParams("parameters3[]"),
                )
                ctx.redirectWithMessage("/actions", if (errorMessage == null) "Action was successfully created." else null, errorMessage)
            }
            config.routes.post("/create_dimmer_action") { ctx ->
                val errorMessage = createDimmerAction(ctx.formParam("description"), ctx.formParams("aliases[]"), ctx.formParams("channels[]"))
                ctx.redirectWithMessage("/actions", if (errorMessage == null) "Action was successfully created." else null, errorMessage)
            }
            config.routes.post("/api/actions/{actionId}") { ctx ->
                apiCallsToday.increment()
                val source = "API (${ctx.ip()})"
                val what = "Action ${ctx.pathParam("actionId")}"
                val json = parseJsonBody(ctx)
                requireValidAuthToken(json.get("auth").toPlainValue(), source, what)
                val action = getAction(ctx.pathParam("actionId"))
                if (action == null || action.actionType != Action.ActionType.Custom) {
                    activityLog.record(source, what, "No such action", succeeded = false)
                    throw HaltException(404, "Action not found.")
                }
                sendOrRefuse(source, action.describe()) { hdlService.performAction(action) }
                ctx.accepted()
            }
            config.routes.post("/api/actions/dimmer/{alias}") { ctx ->
                apiCallsToday.increment()
                val source = "API (${ctx.ip()})"
                val areaAlias = ctx.pathParam("alias").trim().lowercase()
                val what = "Dim \"$areaAlias\""
                val json = parseJsonBody(ctx)
                requireValidAuthToken(json.get("auth").toPlainValue(), source, what)
                val valueInPercent = parsePercent(json.get("value").toPlainValue())
                val action = actionAliasesConcurrentMap[areaAlias]?.let { actionsConcurrentMap[it] }
                if (action == null) {
                    logger.info("Found no dimmer action for alias [{}].", areaAlias)
                    activityLog.record(source, what, "No such area", succeeded = false)
                    throw HaltException(404, "Action not found.")
                }
                sendOrRefuse(source, "${action.describe()} to $valueInPercent%") { hdlService.performDimmerAction(action, valueInPercent) }
                ctx.accepted()
            }
            config.routes.post("/delete_action") { ctx ->
                val deletedAction = removeAction(ctx.formParam("actionId"))
                if (deletedAction != null) {
                    ctx.redirectWithMessage("/actions", "Action was successfully deleted.", null)
                } else {
                    ctx.redirectWithMessage("/actions", null, "Unable to find action.")
                }
            }
            config.routes.post("/delete_component") { ctx ->
                val component = ctx.formParam("componentId")?.let(componentsConcurrentMap::remove)
                if (component != null) {
                    database.commit()
                    ctx.redirectWithMessage("/", "Removed ${component.remark.orEmpty().ifEmpty { "the device" }} (${component.subnet}/${component.deviceId}). If it's still on the bus, the next search finds it again.", null)
                } else {
                    ctx.redirectWithMessage("/", null, "Unable to find the device.")
                }
            }
            config.routes.post("/search_for_components") { ctx ->
                val message = if (hdlService.searchForComponents()) "Searching." else "Already searching."
                ctx.redirectWithMessage("/", "$message Reload the page in about 10 seconds to see what was found.", null)
            }
            config.routes.post("/open_pairing") { ctx ->
                val pairing = pairing
                if (pairing == null) {
                    ctx.redirectWithMessage("/settings", null, "Turn on Let the Android app connect first.")
                } else {
                    pairing.open()
                    ctx.redirectWithMessage("/settings", "Scan the QR code with the app, at home.", null)
                }
            }
            config.routes.post("/remove_phone") { ctx ->
                val phone = ctx.formParam("phoneId")?.let(phones::remove)
                if (phone == null) {
                    ctx.redirectWithMessage("/settings", null, "Unable to find the phone.")
                } else {
                    relay?.disconnect(phone.id)
                    activityLog.record("Settings (${ctx.ip()})", "Remove phone \"${phone.name}\"", "Removed", succeeded = true)
                    ctx.redirectWithMessage("/settings", "Removed ${phone.name}. It can't reach the bus through Busbridge any longer.", null)
                }
            }
            config.routes.post("/cancel_pairing") { ctx ->
                pairing?.cancel()
                ctx.redirectWithMessage("/settings", "Pairing is closed.", null)
            }
            config.routes.post("/update_settings") { ctx ->
                val authKey = ctx.formParam("authKey")?.trim()
                if (authKey != null && authKey.length >= MIN_AUTH_TOKEN_LENGTH) {
                    configConcurrentMap[AUTH_TOKEN_KEY] = authKey
                    val checkForUpdates = ctx.formParam("checkForUpdates") != null
                    configConcurrentMap[CHECK_FOR_UPDATES_KEY] = checkForUpdates.toString()
                    val wasAppPortOn = isAppPortOn
                    configConcurrentMap[APP_PORT_ON_KEY] = (ctx.formParam("appPortOn") != null).toString()
                    database.commit()
                    if (checkForUpdates) {
                        updateCheck.checkSoon()
                    }
                    updateAppPort()
                    // Turned on to connect a phone, so the QR code is there right away
                    val pairing = pairing
                    if (!wasAppPortOn && pairing != null) {
                        pairing.open()
                        ctx.redirectWithMessage("/settings", "Settings successfully updated. Scan the QR code with the app, at home.", null)
                    } else {
                        ctx.redirectWithMessage("/settings", "Settings successfully updated", null)
                    }
                } else {
                    ctx.redirectWithMessage("/settings", null, "Authorization key must be at least $MIN_AUTH_TOKEN_LENGTH characters.")
                }
            }
        }.start(port)
        updateAppPort()
        return this
    }

    /** Opens or closes the port the Android app connects to, as Settings says. */
    @Synchronized
    private fun updateAppPort() {
        if (!isAppPortOn) {
            closeAppPort()
            appPortProblem = null
            return
        }
        if (appServer != null) {
            return
        }
        if (databaseFile != null && !isOwnerOnly(databaseFile)) {
            logger.error("The app port stays closed: {} must be readable by its owner only, since its private key would be kept there.", databaseFile)
            appPortProblem = AppPortProblem.DATABASE_NOT_PRIVATE
            return
        }
        val certificate = appCertificate ?: AppCertificate.loadOrCreate(configConcurrentMap, database::commit).also { appCertificate = it }
        val serverId = configConcurrentMap[SERVER_ID_KEY] ?: UUID.randomUUID().toString().also {
            configConcurrentMap[SERVER_ID_KEY] = it
            database.commit()
        }
        val hdlService = checkNotNull(hdlService) { "The app port can't open without an HdlService" }
        val pairing = Pairing()
        val bus = object : Relay.Bus {
            override fun address(): InetAddress? = hdlService.ipAddress
            override fun broadcast(packet: ByteArray) = hdlService.sendBroadcastMessage(packet)
        }
        val relay = Relay(serverId, VERSION, phones, pairing, bus, activityLog, { relayAddresses(hdlService) })
        appServer = try {
            AppServer(certificate, serverId, VERSION, pairing, relay).start(appPort)
        } catch (e: RuntimeException) {
            logger.error("Couldn't open the app port {}.", appPort, e)
            relay.close()
            null
        }
        if (appServer != null) {
            this.pairing = pairing
            this.relay = relay
            hdlService.setPackageListener(relay::forward)
        }
        appPortProblem = if (appServer == null) AppPortProblem.PORT_TAKEN else null
    }

    private fun closeAppPort() {
        hdlService?.setPackageListener(null)
        appServer?.close()
        appServer = null
        relay?.close()
        relay = null
        pairing = null
    }

    /** What Settings shows of pairing and the paired phones, which it keeps up to date. */
    private fun phonesParams(): Map<String, Any?> {
        val pairing = pairing
        val window = pairing?.window()
        val host = hdlService?.ipAddress?.hostAddress
        val certificate = appCertificate
        val port = appServer?.port()
        val view = if (window != null && host != null && certificate != null && port != null) {
            PairingView(
                Pairing.link(host, port, certificate.pin, window.token),
                Pairing.intentLink(host, port, certificate.pin, window.token),
                window.closesAt,
            )
        } else {
            null
        }
        return mapOf(
            "pairingOpen" to (window != null),
            "pairingView" to view,
            "connecting" to pairing?.connecting().orEmpty(),
            "phones" to phones.all(),
        )
    }

    /** Where a phone can reach this server: at home, and on the VPN from away. */
    private fun relayAddresses(hdlService: HdlService): List<String> =
        listOfNotNull(hdlService.ipAddress?.hostAddress) + BusNetwork.vpnAddresses().map { it.hostAddress }

    fun port(): Int = checkNotNull(javalin) { "The server isn't started" }.port()

    /** The port the Android app connects to, or null when it's closed. */
    @Synchronized
    fun appPort(): Int? = appServer?.port()

    @Synchronized
    fun stop() {
        closeAppPort()
        livePages?.close()
        livePages = null
        updateCheck?.close()
        updateCheck = null
        javalin?.stop()
        javalin = null
    }

    /**
     * @return the deleted action, or null if there was no such action.
     */
    @Synchronized
    fun removeAction(actionId: String?): Action? {
        val id = actionId?.toIntOrNull()
        if (id == null) {
            logger.info("Got invalid action id [{}] to delete.", actionId)
            return null
        }
        val deletedAction = actionsConcurrentMap.remove(id) ?: return null
        // Custom actions from earlier versions may have no description
        val aliases = listOfNotNull(deletedAction.description) + deletedAction.aliases.orEmpty()
        for (alias in aliases) {
            // Only remove aliases pointing to this action, in case another action has since taken the name
            actionAliasesConcurrentMap.remove(alias.trim().lowercase(), deletedAction.id)
        }
        database.commit()
        return deletedAction
    }

    /**
     * @return an error message, or null if the action was created.
     */
    @Synchronized
    private fun createCustomAction(
        description: String?, componentIds: List<String>, operations: List<String>,
        parameters1: List<String>, parameters2: List<String>, parameters3: List<String>,
    ): String? {
        if (componentIds.isEmpty() || operations.size != componentIds.size) {
            return "An action needs at least one command."
        }
        val action = Action(Action.ActionType.Custom, getNextActionId(), description?.trim().orEmpty())
        val knownAddresses = componentsConcurrentMap.values.map { it.subnet to it.deviceId }.toSet()
        for ((i, componentId) in componentIds.withIndex()) {
            val ids = componentId.split(":")
            val subnetId = ids.getOrNull(0)?.toIntOrNull()
            val deviceId = ids.getOrNull(1)?.toIntOrNull()
            // Only devices and operations the page offers, with the values the operation takes, can reach the bus
            val operation = operations[i].toIntOrNull()?.let(Operation::of)
            if (ids.size != 2 || subnetId == null || deviceId == null || (subnetId to deviceId) !in knownAddresses || operation == null) {
                return "Unknown device or operation in command ${i + 1}."
            }
            val parameters = try {
                operation.parse(listOf(parameters1.getOrNull(i), parameters2.getOrNull(i), parameters3.getOrNull(i)))
            } catch (e: IllegalArgumentException) {
                return "Command ${i + 1}: ${e.message}"
            }
            action.addCommand(Action.Command(subnetId, deviceId, operation.code, parameters[0], parameters[1], parameters[2]))
        }
        actionsConcurrentMap[action.id] = action
        database.commit()
        return null
    }

    /**
     * @return an error message, or null if the action was created.
     */
    @Synchronized
    private fun createDimmerAction(description: String?, aliases: List<String>, idsAndChannels: List<String>): String? {
        val areaName = description?.trim()
        if (areaName.isNullOrEmpty()) {
            return "An area name is needed."
        }
        if (idsAndChannels.isEmpty()) {
            return "An action needs at least one dimmer channel."
        }
        val action = Action(Action.ActionType.Dimmer, getNextActionId(), areaName)
        val aliasKeys = linkedSetOf(areaName.lowercase())
        for (alias in aliases.map(String::trim).filter(String::isNotEmpty)) {
            aliasKeys += alias.lowercase()
            action.addAlias(alias)
        }
        aliasKeys.firstOrNull(actionAliasesConcurrentMap::containsKey)?.let { usedAlias ->
            return "The name \"$usedAlias\" is already used by another action."
        }
        for ((i, idsAndChannel) in idsAndChannels.withIndex()) {
            val ids = idsAndChannel.split(":").map(String::toIntOrNull)
            if (ids.size != 3 || ids.any { it == null }) {
                return "Unknown dimmer channel in command ${i + 1}."
            }
            val (subnetId, deviceId, channel) = ids.filterNotNull()
            action.addCommand(Action.Command(subnetId, deviceId, Operation.SingleChannelControl.code, channel, null, null))
        }
        actionsConcurrentMap[action.id] = action
        for (aliasKey in aliasKeys) {
            actionAliasesConcurrentMap[aliasKey] = action.id
        }
        database.commit()
        return null
    }

    private fun getNextActionId(): Int {
        var actionId = actionsConcurrentMap.size + 1
        while (actionsConcurrentMap.containsKey(actionId)) {
            actionId++
        }
        return actionId
    }

    private fun getAction(actionId: String): Action? =
        actionId.toIntOrNull()?.let(actionsConcurrentMap::get).also {
            if (actionId.toIntOrNull() == null) {
                logger.info("Got invalid action id [{}].", actionId)
            }
        }

    private fun parseJsonBody(ctx: Context): ObjectNode {
        try {
            val json = objectMapper.readTree(readBody(ctx, MAX_API_BODY_BYTES))
            if (json is ObjectNode) {
                return json
            }
        } catch (e: JacksonException) {
            logger.info("Got invalid JSON from [{}]: {}", ctx.ip(), e.originalMessage)
        }
        throw HaltException(400, "{\"status\":\"error\",\"message\":\"Body must be a JSON object.\"}")
    }

    /** The part of a page that [LivePages] keeps up to date. */
    private fun renderLive(template: String, hdlService: HdlService): String {
        val params = when (template) {
            "live/status" -> mapOf("status" to status(hdlService))
            "live/activity" -> mapOf("entries" to activityLog.entries())
            "live/phones" -> phonesParams() + ("appPortOpen" to (appServer != null))
            else -> throw IllegalArgumentException("No live template $template")
        }
        val html = StringOutput()
        templateEngine.render("$template.kte", params, html)
        return html.toString()
    }

    private fun render(ctx: Context, name: String, vararg extra: Pair<String, Any?>) {
        val page = Page(
            name = name,
            authToken = configConcurrentMap[AUTH_TOKEN_KEY].orEmpty(),
            components = componentsConcurrentMap.values.sortedWith(compareBy(HdlComponent::getSubnet, HdlComponent::getDeviceId)),
            actions = actionsConcurrentMap.values,
            host = ctx.host().orEmpty(),
            successMessage = ctx.queryParam("success"),
            errorMessage = ctx.queryParam("error"),
            scheme = shownScheme(ctx.scheme(), ctx.header("X-Forwarded-Proto"), ctx.header("Forwarded")),
        )
        val html = StringOutput()
        templateEngine.render("$name.kte", mapOf("page" to page, *extra), html)
        ctx.contentType("text/html; charset=utf-8").result(html.toString())
    }

    private fun requireLocalRequest(ctx: Context) {
        if (!isLocalRequest(ctx.ip(), ctx::header)) {
            logger.warn("Access denied for resource [{}] for IP address [{}]. User agent is [{}].", ctx.path(), ctx.ip(), ctx.userAgent())
            throw HaltException(401, "Access denied. Only allowing access from local addresses.")
        }
    }

    /**
     * Guards against DNS rebinding, where a web page on a domain that resolves to this server's local address could
     * otherwise read the authorization key from these pages.
     */
    private fun requireAllowedHost(ctx: Context) {
        if (!isAllowedHost(ctx.host(), allowedHosts)) {
            logger.warn("Access denied for resource [{}] for host name [{}].", ctx.path(), ctx.host())
            throw HaltException(403, "Access denied. Use this server's IP address, or start it with -D$ALLOWED_HOSTS_PROPERTY=<host name> to allow that name.")
        }
    }

    /**
     * Guards against cross-site request forgery: a web page elsewhere making a browser on the local network post to
     * this server.
     */
    private fun requireSameOrigin(ctx: Context) {
        val origin = ctx.header("Origin")
        if (!isSameOrigin(origin ?: ctx.header("Referer"), ctx.host())) {
            logger.warn("Denied [{}] from origin [{}] and referrer [{}] for host [{}].", ctx.path(), origin, ctx.header("Referer"), ctx.host())
            throw HaltException(403, "Access denied. The request didn't come from this server's own pages.")
        }
    }

    private fun requireValidAuthToken(incomingAuthToken: Any?, source: String, what: String) {
        if (!isValidAuthToken(incomingAuthToken, configConcurrentMap[AUTH_TOKEN_KEY])) {
            activityLog.record(source, what, "Refused: wrong key", succeeded = false)
            throw HaltException(401, "Invalid authentication token.")
        }
    }

    /**
     * Runs an action from the web page: a custom action as it is, a dimmer action at the given level.
     *
     * @return what to tell the user, and whether it was sent.
     */
    fun runAction(hdlService: HdlService, actionId: String?, value: String?, source: String): Pair<String, Boolean> {
        val action = actionId?.let(::getAction) ?: return "Unable to find action." to false
        return try {
            if (action.actionType == Action.ActionType.Dimmer) {
                val valueInPercent = value?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: return "The level must be a number from 0 to 100." to false
                sendOrRefuse(source, "${action.describe()} to $valueInPercent%") { hdlService.performDimmerAction(action, valueInPercent) }
                "Sent \"${action.describe()}\" to $valueInPercent%." to true
            } else {
                sendOrRefuse(source, action.describe()) { hdlService.performAction(action) }
                "Sent \"${action.describe()}\"." to true
            }
        } catch (e: HaltException) {
            TOO_BUSY_MESSAGE to false
        }
    }

    /**
     * Sends an action and logs it, or, if too many are already waiting, logs that and answers 503.
     */
    private fun sendOrRefuse(source: String, what: String, send: () -> Unit) {
        try {
            send()
        } catch (e: RejectedExecutionException) {
            activityLog.record(source, what, "Too busy, not sent", succeeded = false)
            throw HaltException(503, "{\"status\":\"error\",\"message\":\"$TOO_BUSY_MESSAGE\"}")
        }
        activityLog.record(source, what, "Sent", succeeded = true)
    }

    private fun status(hdlService: HdlService): Status {
        val runtime = Runtime.getRuntime()
        val entries = activityLog.entries()
        val apiCalls = entries.filter { it.source.startsWith("API") }
        return Status(
            version = VERSION ?: "(development build)",
            javaVersion = System.getProperty("java.version"),
            startedAt = startedAt,
            now = Instant.now(),
            busMessages = hdlService.busStats.totalMessages,
            lastBusMessage = hdlService.busStats.lastMessage,
            busMessagesPerMinute = hdlService.busStats.messagesPerMinute(),
            components = componentsConcurrentMap.size,
            actions = actionsConcurrentMap.size,
            lastApiCall = apiCalls.firstOrNull(),
            apiCallsToday = apiCallsToday.today(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
            memoryMaxMb = runtime.maxMemory() / 1_048_576,
            cpuTemperature = Status.cpuTemperature(),
            newerVersion = updateCheck?.newerVersion,
        )
    }

    companion object {

        private val logger = LogManager.getLogger(HdlServer::class.java)

        const val DEFAULT_PORT = 4567 // Spark's default, which IFTTT applets made for earlier versions use

        /** The port to listen on, if not [DEFAULT_PORT]. */
        const val PORT_PROPERTY = "hdlserver.port"
        const val DATABASE_FILE = "hdlserver.db"
        const val AUTH_TOKEN_KEY = "authToken"
        const val CHECK_FOR_UPDATES_KEY = "checkForUpdates"
        const val APP_PORT_ON_KEY = "appPortOn"
        const val SERVER_ID_KEY = "serverId"

        /** The running version, e.g. "2.1.0"; null when not run from a built JAR. */
        private val VERSION: String? = HdlServer::class.java.`package`.implementationVersion
        const val MIN_AUTH_TOKEN_LENGTH = 25

        /** Comma-separated host names, besides IP addresses and local names, that the web pages may be reached by. */
        const val ALLOWED_HOSTS_PROPERTY = "hdlserver.allowedHosts"

        const val ASSETS_PATH = "/assets"

        /**
         * Everything the pages load comes from this server, and no script is written into a page, so even a value that
         * slipped past the templates' escaping couldn't run. Only the Demo page embeds a video from YouTube.
         */
        const val CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "frame-src https://www.youtube-nocookie.com; form-action 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'"

        /** The pages need none of the browser's sensitive features, so not even injected code could ask for them. */
        const val PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), " +
            "hid=(), midi=(), display-capture=(), screen-wake-lock=(), xr-spatial-tracking=(), browsing-topics=()"

        /** IFTTT's requests are well under 1 KiB; anything much bigger is someone trying to use up the memory. */
        const val MAX_API_BODY_BYTES = 16 * 1024

        private const val METHOD_OVERRIDE_HEADER = "X-HTTP-Method-Override"
        private const val TOO_BUSY_MESSAGE = "Too many actions are waiting to be sent. Try again shortly."

        /** Methods that can't change anything, so they need no same-origin check. */
        private val SAFE_METHODS = setOf("GET", "HEAD")
        // .ts.net names are Tailscale's MagicDNS, which only resolve inside the VPN
        private val LOCAL_HOST_NAME_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net")
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val FORWARDED_PROTO = Regex("""proto="?(https?)""", RegexOption.IGNORE_CASE)
        private val FORWARDED_FOR = Regex("""for="?\[?([^\]";,]+)""", RegexOption.IGNORE_CASE)

        /** Headers proxies add without saying who they forward for. */
        private val PROXY_HEADERS = listOf("Via", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Server", "X-Forwarded-Port")

        @JvmStatic
        fun main(args: Array<String>) {
            // Before anything is started, so a mistyped port stops the server instead of leaving it half running
            val port = parsePort(System.getProperty(PORT_PROPERTY))
            val appPort = parsePort(System.getProperty(AppServer.PORT_PROPERTY), AppServer.PORT_PROPERTY, AppServer.DEFAULT_PORT)
            val busPort = parsePort(System.getProperty(HdlService.BUS_PORT_PROPERTY), HdlService.BUS_PORT_PROPERTY, HdlService.DEFAULT_BUS_PORT)
            val database = openDatabase(DATABASE_FILE)
            Runtime.getRuntime().addShutdownHook(Thread(database::close))
            val hdlService = HdlService(database, busPort)
            hdlService.start()
            HdlServer(database, hdlService, Path.of(DATABASE_FILE)).start(port, appPort)
        }

        /**
         * Opens the database, which only its owner may read: it holds the API key and the app port's private key.
         */
        @JvmStatic
        fun openDatabase(file: String): DB {
            val database = DBMaker.fileDB(file).fileMmapEnable().checksumHeaderBypass().make()
            try {
                Files.setPosixFilePermissions(Path.of(file), PosixFilePermissions.fromString("rw-------"))
            } catch (e: UnsupportedOperationException) {
                logger.debug("{} has no POSIX file permissions to set.", file) // Windows, where the folder's are used
            } catch (e: IOException) {
                logger.warn("Couldn't make {} readable by its owner only.", file, e)
            }
            return database
        }

        @JvmStatic
        fun isApiPath(path: String?): Boolean = path != null && path.startsWith("/api/")

        /**
         * A request is local when it comes from a local address, and, if it went through a proxy, every address the
         * proxy says it was forwarded for is local too. Otherwise, a proxy on the local network would let anyone in. A
         * proxy that says it's one but not who it forwards for isn't trusted. One that doesn't say it's a proxy at all
         * can't be told apart from a browser, which is why only /api/ should ever be passed on to this server.
         */
        @JvmStatic
        fun isLocalRequest(remoteAddress: String, headers: (String) -> String?): Boolean {
            val addresses = mutableListOf(remoteAddress)
            headers("X-Forwarded-For")?.let { addresses += it.split(",") }
            headers("X-Real-IP")?.let { addresses += it }
            headers("Forwarded")?.let { forwarded ->
                val forwardedFor = FORWARDED_FOR.findAll(forwarded).map { it.groupValues[1] }.toList()
                if (forwardedFor.isEmpty()) {
                    return false
                }
                addresses += forwardedFor
            }
            if (addresses.size == 1 && PROXY_HEADERS.any { headers(it) != null }) {
                return false
            }
            return addresses.all { isLocalAddress(it.trim()) }
        }

        @JvmStatic
        fun isLocalAddress(address: String?): Boolean {
            if (address.isNullOrEmpty() || !(IPV4_LITERAL.matches(address) || address.contains(':'))) {
                return false // Only IP addresses, so that this never does a DNS lookup
            }
            return try {
                val inetAddress = InetAddress.getByName(address)
                val bytes = inetAddress.address
                val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC // fc00::/7, also Tailscale's IPv6
                // 100.64.0.0/10, where Tailscale gives out addresses. Packets claiming it from the Internet can't get answers back:
                // Tailscale routes the range into the VPN, so a spoofed connection never gets established.
                val isTailscale = bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64
                // fe80::/10 and 169.254.0.0/16 only exist on the network they're on: routers don't pass them on
                inetAddress.isSiteLocalAddress || inetAddress.isLinkLocalAddress || isUniqueLocalIpv6 || isTailscale
            } catch (e: UnknownHostException) {
                logger.info("Got invalid IP address [{}].", address)
                false
            }
        }

        /**
         * Whether only the file's owner can read and write it. Never on Windows, which has no POSIX file permissions.
         */
        // TODO: Check Windows' access lists too (AclFileAttributeView), so the app port can open there
        @JvmStatic
        fun isOwnerOnly(file: Path): Boolean = try {
            Files.getPosixFilePermissions(file).all { it == PosixFilePermission.OWNER_READ || it == PosixFilePermission.OWNER_WRITE }
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: IOException) {
            false
        }

        @JvmStatic
        fun isAllowedHost(hostHeader: String?, allowedHosts: Set<String>): Boolean {
            if (hostHeader.isNullOrEmpty()) {
                return false
            }
            var host = hostHeader.lowercase()
            if (host.startsWith("[")) {
                return host.indexOf(']') > 0 // IPv6 literal
            }
            host = host.substringBeforeLast(':').removeSuffix(".")
            return IPV4_LITERAL.matches(host) || !host.contains('.') || host in allowedHosts ||
                LOCAL_HOST_NAME_SUFFIXES.any(host::endsWith)
        }

        /**
         * A mistyped port stops the server with a clear message, rather than quietly listening somewhere IFTTT doesn't call.
         */
        @JvmStatic
        @JvmOverloads
        fun parsePort(port: String?, property: String = PORT_PROPERTY, defaultPort: Int = DEFAULT_PORT): Int {
            if (port.isNullOrBlank()) {
                return defaultPort
            }
            return port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("-D$property must be a port number from 1 to 65535, not \"$port\".")
        }

        /**
         * Whether the browser opened the page over HTTPS, directly or through a proxy that says so. Only used for showing
         * addresses on the pages; a proxy's headers never make a request more trusted (see [isLocalRequest]).
         */
        @JvmStatic
        fun shownScheme(scheme: String, xForwardedProto: String?, forwarded: String?): String {
            val proxiedScheme = xForwardedProto?.substringBefore(',')?.trim() ?: forwarded?.let { FORWARDED_PROTO.find(it)?.groupValues?.get(1) }
            return if (scheme.equals("https", ignoreCase = true) || proxiedScheme.equals("https", ignoreCase = true)) "https" else "http"
        }

        /**
         * Addresses, like "1/40", that more than one device heard on the bus answers at: a mistake in their setup, which
         * makes commands to that address reach both.
         */
        @JvmStatic
        fun sharedAddresses(components: Collection<HdlComponent>, heard: Set<String>): Set<String> =
            components.filter { HdlUtil.getComponentId(it) in heard }
                .groupingBy { "${it.subnet}/${it.deviceId}" }.eachCount()
                .filterValues { it > 1 }.keys

        @JvmStatic
        fun parseAllowedHosts(hosts: String?): Set<String> =
            hosts.orEmpty().split(",").map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()

        @JvmStatic
        fun isSameOrigin(originOrReferer: String?, hostHeader: String?): Boolean {
            if (originOrReferer == null || hostHeader == null) {
                return false
            }
            return try {
                URI(originOrReferer).rawAuthority?.equals(hostHeader, ignoreCase = true) == true
            } catch (e: URISyntaxException) {
                false
            }
        }

        @JvmStatic
        fun isValidAuthToken(incomingAuthToken: Any?, authToken: String?): Boolean {
            if (incomingAuthToken !is String || incomingAuthToken.isEmpty() || authToken == null) {
                return false
            }
            // Constant time, so the token can't be guessed a character at a time from response times
            return MessageDigest.isEqual(authToken.toByteArray(), incomingAuthToken.toByteArray())
        }

        @JvmStatic
        fun parsePercent(value: Any?): Int {
            val number = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().replace("%", "").toDoubleOrNull()
                else -> null
            } ?: throw HaltException(400, "{\"status\":\"error\",\"message\":\"Value must be a number from 0 to 100.\"}")
            return Math.round(number).coerceIn(0, 100).toInt()
        }

        /**
         * Reads the body without ever holding more than maxBytes of it, also when it's sent chunked without a length.
         */
        private fun readBody(ctx: Context, maxBytes: Int): String {
            if (ctx.req().contentLengthLong > maxBytes) {
                throw HaltException(413, "{\"status\":\"error\",\"message\":\"Body is too large.\"}")
            }
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                ctx.bodyInputStream().use { inputStream ->
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        if (body.size() + read > maxBytes) {
                            throw HaltException(413, "{\"status\":\"error\",\"message\":\"Body is too large.\"}")
                        }
                        body.write(buffer, 0, read)
                    }
                }
            } catch (e: IOException) {
                logger.info("Got IOException while reading body from [{}]: {}", ctx.ip(), e.toString())
                throw HaltException(400, "{\"status\":\"error\",\"message\":\"Unable to read body.\"}")
            }
            return body.toString(Charsets.UTF_8)
        }

        private fun generateAuthToken(): String = BigInteger(130, SecureRandom()).toString(32).uppercase()

        /**
         * The decoded path with dot segments resolved, so /api/../settings counts as /settings. Jetty refuses to decode
         * ambiguous paths (e.g. /api/%2e%2e/settings or //settings), and so does this server.
         */
        private fun Context.normalizedPath(): String = try {
            req().pathInfo ?: path()
        } catch (e: IllegalArgumentException) {
            logger.info("Got ambiguous path [{}] from [{}]: {}", path(), ip(), e.message)
            throw HaltException(400, "Bad request.")
        }

        /** "Open the garage", or "Action 7" for custom actions from earlier versions that have no description */
        private fun Action.describe(): String = description?.takeIf(String::isNotBlank) ?: "Action $id"

        private fun Context.accepted() {
            status(202).contentType("application/json").result("{\"status\":\"ok\"}")
        }

        private fun Context.redirectWithMessage(path: String, successMessage: String?, errorMessage: String?) {
            val query = buildString {
                successMessage?.let { append("&success=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
                errorMessage?.let { append("&error=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
            }
            redirect("$path?$query")
        }

        private fun JsonNode?.toPlainValue(): Any? = when {
            this == null || isNull -> null
            isTextual -> textValue()
            isIntegralNumber -> longValue()
            isNumber -> doubleValue()
            isBoolean -> booleanValue()
            else -> toString()
        }

        @Suppress("UNCHECKED_CAST") // MapDB maps opened without serializers hold whatever the earlier versions stored
        private fun <K, V> DB.openMap(name: String): ConcurrentMap<K, V> = hashMap(name).createOrOpen() as ConcurrentMap<K, V>
    }

}

/**
 * Stops a request with this status and body, as Spark's halt() did, so responses stay as earlier versions sent them.
 */
class HaltException(val status: Int, val body: String) : RuntimeException(body, null, false, false)
