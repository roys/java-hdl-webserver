package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.service.HdlService;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ModelAndView;
import spark.Request;
import spark.template.velocity.VelocityTemplateEngine;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static spark.Spark.*;

public class Application {

    private static Logger logger = LoggerFactory.getLogger(Application.class.getSimpleName());

    protected final static boolean DEVEL_MODE = true;

    protected HdlService hdlService;
    protected DB database;
    protected ConcurrentMap configConcurrentMap;
    protected ConcurrentMap componentsConcurrentMap;
    protected ConcurrentMap actionsConcurrentMap;
    protected List<HdlComponent> hdlComponents;

    public Application() {
        setUpDatabase();
        setUpConfig();
        setUpHdlService();
        setUpServer();
        setUpPaths();
    }

    public static void main(String[] args) {
        System.out.println("3");
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

    protected void setUpPaths() {
        get("/", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "index");
        }, new VelocityTemplateEngine());
        get("/actions", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "actions");
        }, new VelocityTemplateEngine());
        post("/create_action", (request, response) -> {
            requireSiteLocalAddress(request);
            // TODO: Error handling and success + error feedback
            createAction(request.queryParams("description"), request.queryParamsValues("components[]"), request.queryParamsValues("operations[]"), request.queryParamsValues("parameters1[]"), request.queryParamsValues("parameters2[]"), request.queryParamsValues("parameters3[]"));
            response.redirect("/actions");
            return null;
        });
        get("/security", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView(req, "security");
        }, new VelocityTemplateEngine());
        post("/api/actions/:actionId", "application/json", (request, response) -> {
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(request.body());
            requireValidAuthToken((String) jsonObject.get("auth"));

            String actionId = request.params(":actionId");

            Action action = null;

            try {
                action = (Action) actionsConcurrentMap.get(Integer.parseInt(actionId));
            } catch (NumberFormatException e) {
                logger.error("Got NumberFormatException while trying to parse action id [" + actionId + "]. Sending back HTTP 404.", e);
            }

            if (action == null) {
                halt(404, "Action not found.");
            }

            hdlService.performAction(action);

            response.status(202);
            response.type("application/json");
            return "{\"status\":\"ok\"}";
        });
    }

    private boolean createAction(String description, String[] componentIds, String[] operations, String[] parameters1, String[] parameters2, String[] parameters3) {
        // TODO: Improve error handling, logging and user feedback
        if (componentIds != null && componentIds.length > 0 && operations != null && operations.length == componentIds.length) { // At least one component + operation
            // TODO: Improve action ID generation:
            int actionId = actionsConcurrentMap.size() + 1;
            while (actionsConcurrentMap.containsKey(actionId)) {
                actionId++;
            }
            Action action = new Action(actionId, description);
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
        model.put("actionUrl", "POST http://" + request.host() + "/api/actions/");
        model.put("curlCommandPart1", "curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"" + configConcurrentMap.get("authToken") + "\"}' http://" + request.host() + "/api/actions/");
        //model.put("curlCommandPart2", " > /dev/null");
        model.put("curlCommandPart2", "");

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
