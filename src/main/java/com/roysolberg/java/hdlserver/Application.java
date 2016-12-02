package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.service.HdlService;
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
            return getModelAndView("index");
        }, new VelocityTemplateEngine());
        get("/commands", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView("commands");
        }, new VelocityTemplateEngine());
        get("/security", (req, res) -> {
            requireSiteLocalAddress(req);
            return getModelAndView("security");
        }, new VelocityTemplateEngine());
    }

    protected ModelAndView getModelAndView(String page) {
        Map<String, Object> model = new HashMap<>();
        model.put("page", page);
        model.put("config", configConcurrentMap);
        if (hdlComponents == null || componentsConcurrentMap.size() != hdlComponents.size()) {
            hdlComponents = new ArrayList<>(componentsConcurrentMap.values());
            hdlComponents.sort((left, right) -> left.getSubnet() != right.getSubnet() ? left.getSubnet() - right.getSubnet() : left.getDeviceId() - right.getDeviceId());
        }
        model.put("components", hdlComponents);

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

}
