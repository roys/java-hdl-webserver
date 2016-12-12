package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.service.HdlService;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import spark.ModelAndView;
import spark.Request;
import spark.template.velocity.VelocityTemplateEngine;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static spark.Spark.*;

public class Application {

    private static Logger logger = LogManager.getLogger(Application.class);

    protected final static boolean DEVEL_MODE = false;

    protected HdlService hdlService;
    protected DB database;
    protected ConcurrentMap configConcurrentMap;
    protected ConcurrentMap componentsConcurrentMap;
    protected ConcurrentMap actionsConcurrentMap;
    protected ConcurrentMap actionAliasesConcurrentMap;
    protected List<HdlComponent> hdlComponents;

    public Application() {
        setUpDatabase();
        setUpConfig();
        setUpHdlService();
        setUpServer();
        setUpPaths();
    }

    public static void main(String[] args) {
        new Application();
    }

    protected void setUpDatabase() {
        database = DBMaker.fileDB("hdlserver.db").fileMmapEnable().checksumHeaderBypass().make();
    }

    protected void setUpConfig() {
        configConcurrentMap = database.hashMap("config").createOrOpen();
        if (!configConcurrentMap.containsKey("authToken")) {
            configConcurrentMap.put("authToken", generateAuthToken());
            database.commit();
        }
        componentsConcurrentMap = database.hashMap("components").createOrOpen();
        actionsConcurrentMap = database.hashMap("actions").createOrOpen();
    }

    protected String generateAuthToken() {
        return new BigInteger(130, new SecureRandom()).toString(32).toUpperCase();
    }

    protected void setUpHdlService() {
        hdlService = new HdlService(database);
    }

    protected void setUpServer() {
        if (DEVEL_MODE) {
            logger.info("***");
            logger.info("*** RUNNING IN DEVELOPMENT MODE");
            logger.info("***");
            staticFiles.externalLocation(System.getProperty("user.dir") + "/src/main/resources/public");
            System.out.println("System.getProperty(\"user.dir\"):" + System.getProperty("user.dir"));
        } else {
            staticFiles.location("/public");
            staticFiles.expireTime(600);
        }
    }

    // TODO: Add status page for HDL service, and settings page for changing auth token and somewhere to see the performed commands and other interesting events
    protected void setUpPaths() {
        before((request, response) -> {
            logger.info("Request: {} - \"{} {} {}\" - \"{}\"", request.ip(), request.requestMethod(), request.pathInfo(), request.protocol(), request.userAgent());
        });
        get("/", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "index");
        }, new VelocityTemplateEngine());
        get("/actions", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "actions");
        }, new VelocityTemplateEngine());
        post("/create_custom_action", (request, response) -> {
            requireSiteLocalAddress(request);
            // TODO: Error handling and success + error feedback
            createCustomAction(request.queryParams("description"), request.queryParamsValues("components[]"), request.queryParamsValues("operations[]"), request.queryParamsValues("parameters1[]"), request.queryParamsValues("parameters2[]"), request.queryParamsValues("parameters3[]"));
            response.redirect("/actions");
            return null;
        });
        post("/create_dimmer_action", (request, response) -> {
            requireSiteLocalAddress(request);
            // TODO: Error handling and success + error feedback
            createDimmerAction(request.queryParams("description"), request.queryParamsValues("channels[]"));
            response.redirect("/actions");
            return null;
        });
        get("/security", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "security");
        }, new VelocityTemplateEngine());
        get("/demo", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "demo");
        }, new VelocityTemplateEngine());
        get("/settings", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "settings");
        }, new VelocityTemplateEngine());
        post("/api/actions/:actionId", "application/json", (request, response) -> {
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(request.body());
            requireValidAuthToken((String) jsonObject.get("auth"));

            String actionId = request.params(":actionId");

            Action action = null;
            printRequestDebugInfo(request);

            try {
                action = (Action) actionsConcurrentMap.get(Integer.parseInt(actionId));
                if (action.getActionType() != Action.ActionType.Custom) {
                    action = null;
                }
            } catch (NumberFormatException e) {
                logger.error("Got NumberFormatException while trying to parse action id [" + actionId + "]. Sending back HTTP 404.", e);
            }

            if (action == null) {
                halt(404, "Action not found.");
            }

            // TODO: Log all performed actions
            hdlService.performDimmerAction(action);

            response.status(202);
            response.type("application/json");
            return "{\"status\":\"ok\"}";
        });
        post("/api/actions/dimmer/:areaId", "application/json", (request, response) -> {
            response.type("application/json");
            Action action = null;
            int valueInPercent = 0;
            try {
                String rawJson = request.body();
                logger.info("Actions:" + actionsConcurrentMap.keySet());
                logger.info("Raw json: [" + rawJson + "]");
                JSONObject jsonObject = (JSONObject) new JSONParser().parse(rawJson);
                requireValidAuthToken((String) jsonObject.get("auth"));

                String areaId = request.params(":areaId");
                logger.info("Area1: [" + areaId + "]");
                areaId = areaId.trim().toLowerCase();
                logger.info("Area2: [" + areaId + "]");

                valueInPercent = ((Long) jsonObject.get("value")).intValue();

                printRequestDebugInfo(request);

                action = (Action) actionsConcurrentMap.get("dimmer-" + areaId);
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("Got exception while handling request.", e);
                halt(500, "{\"status\":\"error\"}");
            }
            if (action == null) {
                halt(404, "Action not found.");
            }

            // TODO: Log all performed actions
            hdlService.performDimmerAction(action, valueInPercent);

            response.status(202);
            return "{\"status\":\"ok\"}";
        });
        post("/delete_action", (request, response) -> {
            String successMessage = null;
            String errorMessage = null;
            String actionId = request.queryParams("actionId");
            logger.info("Actions:" + actionsConcurrentMap.keySet());
            logger.info("actionId:" + actionId);
            try {
                if (actionsConcurrentMap.remove(Integer.parseInt(actionId)) != null) {
                    successMessage = "Action was successfully deleted.";
                } else {
                    errorMessage = "Unable to find action.";
                }
            } catch (NumberFormatException e) {
                logger.error("Got NumberFormatException while trying to parse action id [" + actionId + "]. Sending back HTTP 404.", e);
            }
            response.redirect("/actions?" + getMessagesQueryParams(successMessage, errorMessage));
            return null;
        });
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

    protected void printRequestDebugInfo(Request request) {
        //if (DEVEL_MODE) {
        System.out.println("Host: " + request.host());
        logger.info("Host: " + request.host());
        System.out.println("IP: " + request.ip());
        logger.info("IP: " + request.ip());
        for (String headerKey : request.headers()) {
            String log = headerKey + "=" + request.headers(headerKey);
            System.out.println(log);
            logger.info(log);
        }
        //}
    }

    protected boolean createCustomAction(String description, String[] componentIds, String[] operations, String[] parameters1, String[] parameters2, String[] parameters3) {
        // TODO: Improve error handling, logging and user feedback
        if (componentIds != null && componentIds.length > 0 && operations != null && operations.length == componentIds.length) { // At least one component + operation
            // TODO: Improve action ID generation:
            int actionId = actionsConcurrentMap.size() + 1;
            while (actionsConcurrentMap.containsKey(actionId)) {
                actionId++;
            }
            Action action = new Action(Action.ActionType.Custom, actionId, description);
            for (int i = 0; i < componentIds.length; i++) {
                String[] ids = componentIds[i].split(":");
                if (ids.length == 2) {
                    try {
                        action.addCommand(new Action.Command(Integer.parseInt(ids[0]), Integer.parseInt(ids[1]), Integer.parseInt(operations[i]), getParameter(parameters1, i), getParameter(parameters2, i), getParameter(parameters3, i)));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            actionsConcurrentMap.put(action.getId(), action);
            database.commit();
            return true;
        }
        return false;
    }

    protected boolean createDimmerAction(String area, String[] idsAndChannel) {
        // TODO: Improve error handling, logging and user feedback
        if (area != null && area.length() > 0 && idsAndChannel != null && idsAndChannel.length > 0) { // At least one dimmer channel
            area = area.trim();
            // TODO: Improve action ID generation:
            int actionId = actionsConcurrentMap.size() + 1;
            while (actionsConcurrentMap.containsKey(actionId)) {
                actionId++;
            }
            Action action = new Action(Action.ActionType.Dimmer, actionId, area);
            for (int i = 0; i < idsAndChannel.length; i++) {
                String[] ids = idsAndChannel[i].split(":");
                if (ids.length == 3) {
                    try {
                        action.addCommand(new Action.Command(Integer.parseInt(ids[0]), Integer.parseInt(ids[1]), 0x0031, Integer.parseInt(ids[2]), null, null));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            actionsConcurrentMap.put("dimmer-" + area.toLowerCase(), action);
            database.commit();
            return true;
        }
        return false;
    }

    protected Integer getParameter(String[] parameters, int i) {
        Integer parameter = null;
        if (i < parameters.length && parameters[i] != null && !"".equals(parameters[i])) {
            try {
                parameter = Integer.parseInt(parameters[i].trim());
            } catch (NumberFormatException e) {
                logger.error("Got NumberFormatException while tyring to parse [" + parameters[i] + "] to an integer. Ignoring problem and not adding parameter to command.", e);
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

        return new ModelAndView(model, "templates/" + page + ".vm");
    }

    protected void requireSiteLocalAddress(Request request) {
        try {
            InetAddress remoteInetAddress = InetAddress.getByName(request.ip());
            if (!remoteInetAddress.isSiteLocalAddress()) {
                if (DEVEL_MODE) {
                    logger.warn("Running in development mode - allowing request from non-local IP address [" + request.ip() + "].");
                } else {
                    logger.warn("Access denied for resource [" + request.url() + "] for IP address [" + request.ip() + "]. User agent is [" + request.userAgent() + "].");
                    halt(401, "Access denied. Only allowing access from local addresses.");
                }
            }
        } catch (UnknownHostException e) {
            logger.error("Got UnknownHostException while trying to resolve address [" + request.ip() + "]. Halting request.");
            halt(401, "Access denied. Unable to verify that request is coming from a local address.");
        }
    }

    protected void requireValidAuthToken(String incomingAuthToken) {
        if (incomingAuthToken == null || incomingAuthToken.length() == 0 || !configConcurrentMap.get("authToken").equals(incomingAuthToken)) {
            halt(401, "Invalid authentication token.");
        }
    }

}
