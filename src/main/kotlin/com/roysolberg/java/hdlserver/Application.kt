package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.roysolberg.java.hdlserver.hdl.Action
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent
import com.roysolberg.java.hdlserver.service.HdlService
import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import io.javalin.Javalin
import io.javalin.http.Context
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
import java.util.concurrent.ConcurrentMap

/**
 * The web server: the API that IFTTT applets call, and the web pages for the local network.
 */
class Application(
    private val database: DB,
    private val hdlService: HdlService?,
) {

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
    private var javalin: Javalin? = null

    init {
        if (!configConcurrentMap.containsKey(AUTH_TOKEN_KEY)) {
            configConcurrentMap[AUTH_TOKEN_KEY] = generateAuthToken()
            database.commit()
        }
    }

    fun start(port: Int): Application {
        val hdlService = checkNotNull(hdlService) { "The server can't start without an HdlService" }
        javalin = Javalin.create { config ->
            config.startup.showJavalinBanner = false
            config.startup.showOldJavalinVersionWarning = false
            // Always the socket's own address: X-Forwarded-For is something anyone can send to look local
            config.contextResolver.ip = { ctx -> ctx.req().remoteAddr }
            config.contextResolver.host = { ctx -> ctx.req().getHeader("Host") }
            config.jetty.modifyHttpConfiguration { httpConfiguration -> httpConfiguration.sendServerVersion = false }

            config.routes.exception(HaltException::class.java) { e, ctx -> ctx.status(e.status).result(e.body) }
            config.routes.before { ctx ->
                logger.info("Request: {} - \"{} {} {}\" - \"{}\"", ctx.ip(), ctx.req().method, ctx.path(), ctx.req().protocol, ctx.userAgent())
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
                    ctx.header("X-Content-Type-Options", "nosniff")
                    ctx.header("Referrer-Policy", "strict-origin-when-cross-origin") // YouTube embeds need the origin
                }
            }

            for (page in listOf("index", "actions", "security", "demo", "settings")) {
                config.routes.get(if (page == "index") "/" else "/$page") { ctx -> render(ctx, page) }
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
                val json = parseJsonBody(ctx)
                requireValidAuthToken(json.get("auth").toPlainValue())
                val action = getAction(ctx.pathParam("actionId"))
                if (action == null || action.actionType != Action.ActionType.Custom) {
                    throw HaltException(404, "Action not found.")
                }
                // TODO: Log all performed actions
                hdlService.performAction(action)
                ctx.accepted()
            }
            config.routes.post("/api/actions/dimmer/{alias}") { ctx ->
                val json = parseJsonBody(ctx)
                requireValidAuthToken(json.get("auth").toPlainValue())
                val valueInPercent = parsePercent(json.get("value").toPlainValue())
                val areaAlias = ctx.pathParam("alias").trim().lowercase()
                val action = actionAliasesConcurrentMap[areaAlias]?.let { actionsConcurrentMap[it] }
                if (action == null) {
                    logger.info("Found no dimmer action for alias [{}].", areaAlias)
                    throw HaltException(404, "Action not found.")
                }
                // TODO: Log all performed actions
                hdlService.performDimmerAction(action, valueInPercent)
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
            config.routes.post("/search_for_components") { ctx ->
                hdlService.doOneTimeQuickSearchForComponents()
                ctx.redirectWithMessage("/", "Performing search. Please refresh page to see any new components found.", null)
            }
            config.routes.post("/update_settings") { ctx ->
                val authKey = ctx.formParam("authKey")?.trim()
                if (authKey != null && authKey.length >= MIN_AUTH_TOKEN_LENGTH) {
                    configConcurrentMap[AUTH_TOKEN_KEY] = authKey
                    database.commit()
                    ctx.redirectWithMessage("/settings", "Settings successfully updated", null)
                } else {
                    ctx.redirectWithMessage("/settings", null, "Authorization key must be at least $MIN_AUTH_TOKEN_LENGTH characters.")
                }
            }
        }.start(port)
        return this
    }

    fun port(): Int = checkNotNull(javalin) { "The server isn't started" }.port()

    fun stop() {
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
        for ((i, componentId) in componentIds.withIndex()) {
            val ids = componentId.split(":")
            val subnetId = ids.getOrNull(0)?.toIntOrNull()
            val deviceId = ids.getOrNull(1)?.toIntOrNull()
            val operation = operations[i].toIntOrNull()
            if (ids.size != 2 || subnetId == null || deviceId == null || operation == null) {
                return "Unknown component or operation in command ${i + 1}."
            }
            action.addCommand(
                Action.Command(
                    subnetId, deviceId, operation,
                    getCommandParameter(parameters1, i), getCommandParameter(parameters2, i), getCommandParameter(parameters3, i),
                ),
            )
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
            action.addCommand(Action.Command(subnetId, deviceId, SINGLE_CHANNEL_CONTROL, channel, null, null))
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

    private fun getCommandParameter(parameters: List<String>, i: Int): Int? {
        val parameter = parameters.getOrNull(i)?.trim()
        if (parameter.isNullOrEmpty()) {
            return null
        }
        return parameter.toIntOrNull().also {
            if (it == null) {
                logger.error("Got invalid parameter [{}]. Not adding it to the command.", parameter)
            }
        }
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

    private fun render(ctx: Context, name: String) {
        val page = Page(
            name = name,
            authToken = configConcurrentMap[AUTH_TOKEN_KEY].orEmpty(),
            components = componentsConcurrentMap.values.sortedWith(compareBy(HdlComponent::getSubnet, HdlComponent::getDeviceId)),
            actions = actionsConcurrentMap.values,
            host = ctx.host().orEmpty(),
            successMessage = ctx.queryParam("success"),
            errorMessage = ctx.queryParam("error"),
        )
        val html = StringOutput()
        templateEngine.render("$name.kte", page, html)
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

    private fun requireValidAuthToken(incomingAuthToken: Any?) {
        if (!isValidAuthToken(incomingAuthToken, configConcurrentMap[AUTH_TOKEN_KEY])) {
            throw HaltException(401, "Invalid authentication token.")
        }
    }

    companion object {

        private val logger = LogManager.getLogger(Application::class.java)

        const val DEFAULT_PORT = 4567 // Spark's default, which IFTTT applets made for earlier versions use
        const val DATABASE_FILE = "hdlserver.db"
        const val AUTH_TOKEN_KEY = "authToken"
        const val MIN_AUTH_TOKEN_LENGTH = 25

        /** Comma-separated host names, besides IP addresses and local names, that the web pages may be reached by. */
        const val ALLOWED_HOSTS_PROPERTY = "hdlserver.allowedHosts"

        /** IFTTT's requests are well under 1 KiB; anything much bigger is someone trying to use up the memory. */
        const val MAX_API_BODY_BYTES = 16 * 1024

        private const val SINGLE_CHANNEL_CONTROL = 0x0031
        private const val METHOD_OVERRIDE_HEADER = "X-HTTP-Method-Override"

        /** Methods that can't change anything, so they need no same-origin check. */
        private val SAFE_METHODS = setOf("GET", "HEAD")
        private val LOCAL_HOST_NAME_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val FORWARDED_FOR = Regex("""for="?\[?([^\]";,]+)""", RegexOption.IGNORE_CASE)

        /** Headers proxies add without saying who they forward for. */
        private val PROXY_HEADERS = listOf("Via", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Server", "X-Forwarded-Port")

        @JvmStatic
        fun main(args: Array<String>) {
            val database = openDatabase(DATABASE_FILE)
            Runtime.getRuntime().addShutdownHook(Thread(database::close))
            val hdlService = HdlService(database)
            hdlService.start()
            Application(database, hdlService).start(DEFAULT_PORT)
        }

        @JvmStatic
        fun openDatabase(file: String): DB = DBMaker.fileDB(file).fileMmapEnable().checksumHeaderBypass().make()

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
                val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC // fc00::/7
                inetAddress.isSiteLocalAddress || isUniqueLocalIpv6
            } catch (e: UnknownHostException) {
                logger.info("Got invalid IP address [{}].", address)
                false
            }
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
