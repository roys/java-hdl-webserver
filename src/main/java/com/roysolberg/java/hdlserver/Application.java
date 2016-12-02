package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.service.HdlService;
import org.mapdb.DB;
import org.mapdb.DBException;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static spark.Spark.*;

public class Application {

    private static Logger logger = LoggerFactory.getLogger(Application.class.getSimpleName());

    protected final static boolean DEVEL_MODE = false;

    protected HdlService hdlService;
    protected DB database;
    protected ConcurrentMap configConcurrentMap;

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
        try {
            database = DBMaker.fileDB("hdlserver.db").fileMmapEnable().make();
        } catch (DBException e) {
            e.printStackTrace();
            database = DBMaker.fileDB("hdlserver.db").fileMmapEnable().checksumHeaderBypass().make();
        }
    }

    protected void setUpConfig() {
        configConcurrentMap = database.hashMap("config").createOrOpen();
        if (!configConcurrentMap.containsKey("authToken")) {
            configConcurrentMap.put("authToken", generateAuthToken());
            database.commit();
        }
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

            Map<String, Object> model = new HashMap<>();
            model.put("hello", "Velocity World");
            model.put("config", configConcurrentMap);

            // The wm files are located under the resources directory
            return new ModelAndView(model, "templates/index.vm");
        }, new VelocityTemplateEngine());
        get("/hello", (req, res) -> "Hello World");
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
