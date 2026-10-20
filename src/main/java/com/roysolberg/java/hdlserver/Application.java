package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.service.HdlService;
import com.roysolberg.java.hdlserver.util.HdlUtil;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.template.velocity.VelocityTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static spark.Spark.*;

public class Application {

    private static final Logger logger = LogManager.getLogger(Application.class);

    protected final static boolean DEVEL_MODE = false;

    public static final int DEFAULT_PORT = 4567; // Spark's default, which IFTTT applets made for earlier versions use
    public static final String DATABASE_FILE = "hdlserver.db";
    public static final String AUTH_TOKEN_KEY = "authToken";
    public static final int MIN_AUTH_TOKEN_LENGTH = 25;
    /** Comma-separated host names, besides IP addresses and local names, that the web pages may be reached by. */
    public static final String ALLOWED_HOSTS_PROPERTY = "hdlserver.allowedHosts";
    /** IFTTT's requests are well under 1 KiB; anything much bigger is someone trying to use up the memory. */
    public static final int MAX_API_BODY_BYTES = 16 * 1024;

    private static final List<String> LOCAL_HOST_NAME_SUFFIXES = Arrays.asList(".local", ".lan", ".home", ".home.arpa", ".internal");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern FORWARDED_FOR = Pattern.compile("for=\"?\\[?([^\\]\";,]+)", Pattern.CASE_INSENSITIVE);
    /** Headers proxies add without saying who they forward for. */
    private static final List<String> PROXY_HEADERS = Arrays.asList("Via", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Server", "X-Forwarded-Port");

    protected final HdlService hdlService;
    protected final DB database;
    protected final Set<String> allowedHosts;
    protected ConcurrentMap configConcurrentMap;
    protected ConcurrentMap componentsConcurrentMap;
    protected ConcurrentMap actionsConcurrentMap;
    protected ConcurrentMap actionAliasesConcurrentMap;
    protected List<HdlComponent> hdlComponents;

    public Application(DB database, HdlService hdlService) {
        this.database = database;
        this.hdlService = hdlService;
        this.allowedHosts = parseAllowedHosts(System.getProperty(ALLOWED_HOSTS_PROPERTY));
        setUpConfig();
    }

    public static void main(String[] args) {
        DB database = openDatabase(DATABASE_FILE);
        Runtime.getRuntime().addShutdownHook(new Thread(database::close));
        HdlService hdlService = new HdlService(database);
        hdlService.start();
        new Application(database, hdlService).start(DEFAULT_PORT);
    }

    public static DB openDatabase(String file) {
        return DBMaker.fileDB(file).fileMmapEnable().checksumHeaderBypass().make();
    }

    public void start(int port) {
        port(port);
        // Otherwise Spark takes the client's address from X-Forwarded-For, which anyone can send to look local
        untrustForwardHeaders();
        setUpServer();
        setUpPaths();
        awaitInitialization();
    }

    protected void setUpConfig() {
        configConcurrentMap = database.hashMap("config").createOrOpen();
        if (!configConcurrentMap.containsKey(AUTH_TOKEN_KEY)) {
            configConcurrentMap.put(AUTH_TOKEN_KEY, generateAuthToken());
            database.commit();
        }
        componentsConcurrentMap = database.hashMap("components").createOrOpen();
        actionsConcurrentMap = database.hashMap("actions").createOrOpen();
        actionAliasesConcurrentMap = database.hashMap("actionAliases").createOrOpen();
    }

    protected String generateAuthToken() {
        return new BigInteger(130, new SecureRandom()).toString(32).toUpperCase(Locale.ROOT);
    }

    protected void setUpServer() {
        // No static files: Spark serves them before any filter, so they would skip the local network check
        if (DEVEL_MODE) {
            logger.info("***");
            logger.info("*** RUNNING IN DEVELOPMENT MODE");
            logger.info("***");
        }
    }

    // TODO: Add status page for HDL service, and somewhere to see the performed commands and other interesting events
    protected void setUpPaths() {
        before((request, response) -> {
            logger.info("Request: {} - \"{} {} {}\" - \"{}\"", request.ip(), request.requestMethod(), request.pathInfo(), request.protocol(), request.userAgent());
        });
        // Everything but the API is the web front end, which is only for the local network.
        before((request, response) -> {
            if (!isApiPath(request.pathInfo())) {
                requireLocalRequest(request);
                requireAllowedHost(request);
                if ("POST".equals(request.requestMethod())) {
                    requireSameOrigin(request);
                }
                response.header("X-Frame-Options", "DENY");
                response.header("X-Content-Type-Options", "nosniff");
                response.header("Referrer-Policy", "strict-origin-when-cross-origin"); // YouTube embeds need the origin
            }
        });
        get("/", (req, res) -> getModelAndView(req, "index"), new VelocityTemplateEngine());
        get("/actions", (req, res) -> getModelAndView(req, "actions"), new VelocityTemplateEngine());
        get("/security", (req, res) -> getModelAndView(req, "security"), new VelocityTemplateEngine());
        get("/demo", (req, res) -> getModelAndView(req, "demo"), new VelocityTemplateEngine());
        get("/settings", (req, res) -> getModelAndView(req, "settings"), new VelocityTemplateEngine());
        post("/create_custom_action", (request, response) -> {
            String errorMessage = createCustomAction(request.queryParams("description"), request.queryParamsValues("components[]"), request.queryParamsValues("operations[]"), request.queryParamsValues("parameters1[]"), request.queryParamsValues("parameters2[]"), request.queryParamsValues("parameters3[]"));
            response.redirect("/actions?" + getMessagesQueryParams(errorMessage == null ? "Action was successfully created." : null, errorMessage));
            return null;
        });
        post("/create_dimmer_action", (request, response) -> {
            String errorMessage = createDimmerAction(request.queryParams("description"), request.queryParamsValues("aliases[]"), request.queryParamsValues("channels[]"));
            response.redirect("/actions?" + getMessagesQueryParams(errorMessage == null ? "Action was successfully created." : null, errorMessage));
            return null;
        });
        post("/api/actions/:actionId", "application/json", (request, response) -> {
            JSONObject jsonObject = parseJsonBody(request);
            requireValidAuthToken(jsonObject.get("auth"));

            Action action = getAction(request.params(":actionId"));
            if (action == null || action.getActionType() != Action.ActionType.Custom) {
                halt(404, "Action not found.");
            }

            // TODO: Log all performed actions
            hdlService.performAction(action);
            return accepted(response);
        });
        post("/api/actions/dimmer/:alias", "application/json", (request, response) -> {
            JSONObject jsonObject = parseJsonBody(request);
            requireValidAuthToken(jsonObject.get("auth"));
            int valueInPercent = parsePercent(jsonObject.get("value"));

            String areaAlias = request.params(":alias").trim().toLowerCase(Locale.ROOT);
            Object actionId = actionAliasesConcurrentMap.get(areaAlias);
            Action action = actionId != null ? (Action) actionsConcurrentMap.get(actionId) : null;
            if (action == null) {
                logger.info("Found no dimmer action for alias [{}].", areaAlias);
                halt(404, "Action not found.");
            }

            // TODO: Log all performed actions
            hdlService.performDimmerAction(action, valueInPercent);
            return accepted(response);
        });
        post("/delete_action", (request, response) -> {
            String successMessage = null;
            String errorMessage = null;
            Action deletedAction = removeAction(request.queryParams("actionId"));
            if (deletedAction != null) {
                successMessage = "Action was successfully deleted.";
            } else {
                errorMessage = "Unable to find action.";
            }
            response.redirect("/actions?" + getMessagesQueryParams(successMessage, errorMessage));
            return null;
        });
        post("/search_for_components", (request, response) -> {
            hdlService.doOneTimeQuickSearchForComponents();
            String successMessage = "Performing search. Please refresh page to see any new components found.";
            response.redirect("/?" + getMessagesQueryParams(successMessage, null));
            return null;
        });
        post("/update_settings", (request, response) -> {
            String successMessage = null;
            String errorMessage = null;
            String authKey = request.queryParams("authKey");
            if (authKey != null && authKey.trim().length() >= MIN_AUTH_TOKEN_LENGTH) {
                configConcurrentMap.put(AUTH_TOKEN_KEY, authKey.trim());
                database.commit();
                successMessage = "Settings successfully updated";
            } else {
                errorMessage = "Authorization key must be at least " + MIN_AUTH_TOKEN_LENGTH + " characters.";
            }
            response.redirect("/settings?" + getMessagesQueryParams(successMessage, errorMessage));
            return null;
        });
    }

    protected static boolean isApiPath(String path) {
        return path != null && path.startsWith("/api/");
    }

    protected static String accepted(Response response) {
        response.status(202);
        response.type("application/json");
        return "{\"status\":\"ok\"}";
    }

    protected JSONObject parseJsonBody(Request request) {
        try {
            Object json = new JSONParser().parse(readBody(request, MAX_API_BODY_BYTES));
            if (json instanceof JSONObject) {
                return (JSONObject) json;
            }
        } catch (ParseException e) {
            logger.info("Got invalid JSON from [{}]: {}", request.ip(), e.toString());
        }
        throw halt(400, "{\"status\":\"error\",\"message\":\"Body must be a JSON object.\"}");
    }

    /**
     * Reads the body without ever holding more than maxBytes of it, also when it's sent chunked without a length.
     */
    protected static String readBody(Request request, int maxBytes) {
        if (request.contentLength() > maxBytes) {
            throw halt(413, "{\"status\":\"error\",\"message\":\"Body is too large.\"}");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream inputStream = request.raw().getInputStream()) {
            for (int read; (read = inputStream.read(buffer)) != -1; ) {
                if (body.size() + read > maxBytes) {
                    throw halt(413, "{\"status\":\"error\",\"message\":\"Body is too large.\"}");
                }
                body.write(buffer, 0, read);
            }
        } catch (IOException e) {
            logger.info("Got IOException while reading body from [{}]: {}", request.ip(), e.toString());
            throw halt(400, "{\"status\":\"error\",\"message\":\"Unable to read body.\"}");
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    protected static int parsePercent(Object value) {
        Number number = null;
        if (value instanceof Number) {
            number = (Number) value;
        } else if (value instanceof String) {
            try {
                number = Double.parseDouble(((String) value).trim().replace("%", ""));
            } catch (NumberFormatException e) {
                // Handled as a missing value below
            }
        }
        if (number == null) {
            throw halt(400, "{\"status\":\"error\",\"message\":\"Value must be a number from 0 to 100.\"}");
        }
        return (int) Math.max(0, Math.min(100, Math.round(number.doubleValue())));
    }

    protected Action getAction(String actionId) {
        try {
            return (Action) actionsConcurrentMap.get(Integer.parseInt(actionId));
        } catch (NumberFormatException e) {
            logger.info("Got invalid action id [{}].", actionId);
            return null;
        }
    }

    protected String getMessagesQueryParams(String successMessage, String errorMessage) throws UnsupportedEncodingException {
        String query = "";
        if (successMessage != null) {
            query += "&success=" + URLEncoder.encode(successMessage, "UTF-8");
        }
        if (errorMessage != null) {
            query += "&error=" + URLEncoder.encode(errorMessage, "UTF-8");
        }
        return query;
    }

    /**
     * @return an error message, or null if the action was created.
     */
    protected synchronized String createCustomAction(String description, String[] componentIds, String[] operations, String[] parameters1, String[] parameters2, String[] parameters3) {
        if (componentIds == null || componentIds.length == 0 || operations == null || operations.length != componentIds.length) {
            return "An action needs at least one command.";
        }
        Action action = new Action(Action.ActionType.Custom, getNextActionId(), description == null ? "" : description.trim());
        for (int i = 0; i < componentIds.length; i++) {
            String[] ids = componentIds[i].split(":");
            if (ids.length != 2) {
                return "Unknown component in command " + (i + 1) + ".";
            }
            try {
                action.addCommand(new Action.Command(Integer.parseInt(ids[0]), Integer.parseInt(ids[1]), Integer.parseInt(operations[i]), getCommandParameter(parameters1, i), getCommandParameter(parameters2, i), getCommandParameter(parameters3, i)));
            } catch (NumberFormatException e) {
                return "Unknown component or operation in command " + (i + 1) + ".";
            }
        }
        actionsConcurrentMap.put(action.getId(), action);
        database.commit();
        return null;
    }

    /**
     * @return an error message, or null if the action was created.
     */
    protected synchronized String createDimmerAction(String description, String[] aliases, String[] idsAndChannel) {
        if (description == null || description.trim().isEmpty()) {
            return "An area name is needed.";
        }
        if (idsAndChannel == null || idsAndChannel.length == 0) {
            return "An action needs at least one dimmer channel.";
        }
        description = description.trim();
        Action action = new Action(Action.ActionType.Dimmer, getNextActionId(), description);
        Set<String> aliasKeys = new HashSet<>();
        aliasKeys.add(description.toLowerCase(Locale.ROOT));
        if (aliases != null) {
            for (String alias : aliases) {
                alias = alias.trim();
                if (!alias.isEmpty()) {
                    aliasKeys.add(alias.toLowerCase(Locale.ROOT));
                    action.addAlias(alias);
                }
            }
        }
        for (String aliasKey : aliasKeys) {
            if (actionAliasesConcurrentMap.containsKey(aliasKey)) {
                return "The name \"" + aliasKey + "\" is already used by another action.";
            }
        }
        for (int i = 0; i < idsAndChannel.length; i++) {
            String[] ids = idsAndChannel[i].split(":");
            if (ids.length != 3) {
                return "Unknown dimmer channel in command " + (i + 1) + ".";
            }
            try {
                action.addCommand(new Action.Command(Integer.parseInt(ids[0]), Integer.parseInt(ids[1]), 0x0031, Integer.parseInt(ids[2]), null, null));
            } catch (NumberFormatException e) {
                return "Unknown dimmer channel in command " + (i + 1) + ".";
            }
        }
        actionsConcurrentMap.put(action.getId(), action);
        for (String aliasKey : aliasKeys) {
            actionAliasesConcurrentMap.put(aliasKey, action.getId());
        }
        database.commit();
        return null;
    }

    /**
     * @return the deleted action, or null if there was no such action.
     */
    protected synchronized Action removeAction(String actionId) {
        Action deletedAction;
        try {
            deletedAction = (Action) actionsConcurrentMap.remove(Integer.parseInt(actionId));
        } catch (NumberFormatException e) {
            logger.info("Got invalid action id [{}] to delete.", actionId);
            return null;
        }
        if (deletedAction != null) {
            List<String> aliases = new ArrayList<>();
            aliases.add(deletedAction.getDescription());
            if (deletedAction.getAliases() != null) {
                aliases.addAll(deletedAction.getAliases());
            }
            for (String alias : aliases) {
                if (alias != null) { // Custom actions from earlier versions may have no description
                    // Only remove aliases pointing to this action, in case another action has since taken the name
                    actionAliasesConcurrentMap.remove(alias.trim().toLowerCase(Locale.ROOT), deletedAction.getId());
                }
            }
            database.commit();
        }
        return deletedAction;
    }

    protected int getNextActionId() {
        int actionId = actionsConcurrentMap.size() + 1;
        while (actionsConcurrentMap.containsKey(actionId)) {
            actionId++;
        }
        return actionId;
    }

    protected Integer getCommandParameter(String[] parameters, int i) {
        Integer parameter = null;
        if (parameters != null && i < parameters.length && parameters[i] != null && !parameters[i].trim().isEmpty()) {
            try {
                parameter = Integer.parseInt(parameters[i].trim());
            } catch (NumberFormatException e) {
                logger.error("Got NumberFormatException while trying to parse [{}] to an integer. Ignoring problem and not adding parameter to command.", parameters[i], e);
            }
        }
        return parameter;
    }

    protected ModelAndView getModelAndView(Request request, String page) {
        Map<String, Object> model = new HashMap<>();
        model.put("page", page);
        model.put("config", configConcurrentMap);
        if (hdlComponents == null || componentsConcurrentMap.size() != hdlComponents.size()) {
            hdlComponents = new ArrayList<>(componentsConcurrentMap.values());
            hdlComponents.sort((left, right) -> left.getSubnet() != right.getSubnet() ? left.getSubnet() - right.getSubnet() : left.getDeviceId() - right.getDeviceId());
        }
        model.put("components", hdlComponents);
        model.put("actions", actionsConcurrentMap.values());
        model.put("host", request.host());
        model.put("successMessage", StringEscapeUtils.escapeHtml(request.queryParams("success")));
        model.put("errorMessage", StringEscapeUtils.escapeHtml(request.queryParams("error")));
        model.put("HdlUtil", HdlUtil.class);
        model.put("esc", StringEscapeUtils.class);

        return new ModelAndView(model, "templates/" + page + ".vm");
    }

    protected void requireLocalRequest(Request request) {
        if (!isLocalRequest(request.ip(), request::headers)) {
            if (DEVEL_MODE) {
                logger.warn("Running in development mode - allowing request from non-local IP address [{}].", request.ip());
            } else {
                logger.warn("Access denied for resource [{}] for IP address [{}]. User agent is [{}].", request.pathInfo(), request.ip(), request.userAgent());
                halt(401, "Access denied. Only allowing access from local addresses.");
            }
        }
    }

    /**
     * A request is local when it comes from a local address, and, if it went through a proxy, every address the proxy
     * says it was forwarded for is local too. Otherwise, a proxy on the local network would let anyone in. A proxy
     * that says it's one but not who it forwards for isn't trusted. One that doesn't say it's a proxy at all can't be
     * told apart from a browser, which is why only /api/ should ever be passed on to this server.
     */
    protected static boolean isLocalRequest(String remoteAddress, Function<String, String> headers) {
        List<String> addresses = new ArrayList<>();
        addresses.add(remoteAddress);
        String xForwardedFor = headers.apply("X-Forwarded-For");
        if (xForwardedFor != null) {
            addresses.addAll(Arrays.asList(xForwardedFor.split(",")));
        }
        String xRealIp = headers.apply("X-Real-IP");
        if (xRealIp != null) {
            addresses.add(xRealIp);
        }
        String forwarded = headers.apply("Forwarded");
        if (forwarded != null) {
            Matcher matcher = FORWARDED_FOR.matcher(forwarded);
            if (!matcher.find()) {
                return false;
            }
            do {
                addresses.add(matcher.group(1));
            } while (matcher.find());
        }
        if (addresses.size() == 1) {
            for (String proxyHeader : PROXY_HEADERS) {
                if (headers.apply(proxyHeader) != null) {
                    return false;
                }
            }
        }
        for (String address : addresses) {
            if (!isLocalAddress(address.trim())) {
                return false;
            }
        }
        return true;
    }

    protected static boolean isLocalAddress(String address) {
        if (address == null || address.isEmpty() || !(IPV4_LITERAL.matcher(address).matches() || address.contains(":"))) {
            return false; // Only IP addresses, so that this never does a DNS lookup
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            byte[] bytes = inetAddress.getAddress();
            boolean isUniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // fc00::/7
            return inetAddress.isSiteLocalAddress() || isUniqueLocalIpv6;
        } catch (UnknownHostException e) {
            logger.info("Got invalid IP address [{}].", address);
            return false;
        }
    }

    /**
     * Guards against DNS rebinding, where a web page on a domain that resolves to this server's local address could
     * otherwise read the authorization key from these pages.
     */
    protected void requireAllowedHost(Request request) {
        if (!isAllowedHost(request.host(), allowedHosts)) {
            logger.warn("Access denied for resource [{}] for host name [{}].", request.pathInfo(), request.host());
            halt(403, "Access denied. Use this server's IP address, or start it with -D" + ALLOWED_HOSTS_PROPERTY + "=<host name> to allow that name.");
        }
    }

    protected static boolean isAllowedHost(String hostHeader, Set<String> allowedHosts) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        String host = hostHeader.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return host.indexOf(']') > 0; // IPv6 literal
        }
        int portIndex = host.lastIndexOf(':');
        if (portIndex >= 0) {
            host = host.substring(0, portIndex);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (IPV4_LITERAL.matcher(host).matches() || !host.contains(".") || allowedHosts.contains(host)) {
            return true;
        }
        for (String suffix : LOCAL_HOST_NAME_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    protected static Set<String> parseAllowedHosts(String hosts) {
        if (hosts == null || hosts.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> allowedHosts = new HashSet<>();
        for (String host : hosts.split(",")) {
            if (!host.trim().isEmpty()) {
                allowedHosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return allowedHosts;
    }

    /**
     * Guards against cross-site request forgery: a web page elsewhere making a browser on the local network post to
     * this server.
     */
    protected void requireSameOrigin(Request request) {
        String origin = request.headers("Origin");
        if (!isSameOrigin(origin != null ? origin : request.headers("Referer"), request.host())) {
            logger.warn("Denied [{}] from origin [{}] and referrer [{}] for host [{}].", request.pathInfo(), origin, request.headers("Referer"), request.host());
            halt(403, "Access denied. The request didn't come from this server's own pages.");
        }
    }

    protected static boolean isSameOrigin(String originOrReferer, String hostHeader) {
        if (originOrReferer == null || hostHeader == null) {
            return false;
        }
        try {
            String authority = new URI(originOrReferer).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(hostHeader);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    protected void requireValidAuthToken(Object incomingAuthToken) {
        if (!isValidAuthToken(incomingAuthToken, (String) configConcurrentMap.get(AUTH_TOKEN_KEY))) {
            halt(401, "Invalid authentication token.");
        }
    }

    protected static boolean isValidAuthToken(Object incomingAuthToken, String authToken) {
        if (!(incomingAuthToken instanceof String) || ((String) incomingAuthToken).isEmpty() || authToken == null) {
            return false;
        }
        // Constant time, so the token can't be guessed a character at a time from response times
        return MessageDigest.isEqual(authToken.getBytes(StandardCharsets.UTF_8), ((String) incomingAuthToken).getBytes(StandardCharsets.UTF_8));
    }

}
