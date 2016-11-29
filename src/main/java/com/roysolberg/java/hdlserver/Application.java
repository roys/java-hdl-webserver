package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.service.HdlService;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static spark.Spark.get;
import static spark.Spark.staticFiles;

public class Application {

    private static final String TAG = Application.class.getSimpleName();

    protected static HdlService hdlService;
    private static DB database;
    private static ConcurrentMap configConcurrentMap;

    public static void main(String[] args) {
        System.out.println("2");
        if (true) {
            staticFiles.location("/public");
            staticFiles.expireTime(600);
        } else {
            staticFiles.externalLocation(System.getProperty("user.dir") + "/src/main/resources/public");
            System.out.println("System.getProperty(\"user.dir\"):" + System.getProperty("user.dir"));
        }
        setUpDatabase();
        setUpConfig();
        setUpHdlService();
        setUpPaths();
    }

    protected static void setUpDatabase() {
        database = DBMaker.fileDB("hdlserver.db").fileMmapEnable().make();
    }

    protected static void setUpConfig() {
        configConcurrentMap = database.hashMap("config").createOrOpen();
        if (!configConcurrentMap.containsKey("authToken")) {
            configConcurrentMap.put("authToken", generateAuthToken());
            database.commit();
        }
    }

    private static String generateAuthToken() {
        return new BigInteger(130, new SecureRandom()).toString(32).toUpperCase();
    }

    protected static void setUpHdlService() {
        hdlService = new HdlService();
    }

    protected static void setUpPaths() {
        get("/", (req, res) -> {
            System.out.println("IP:" + req.ip());
            Map<String, Object> model = new HashMap<>();
            model.put("hello", "Velocity World");
            model.put("config", configConcurrentMap);

            // The wm files are located under the resources directory
            return new ModelAndView(model, "templates/index.vm");
        }, new VelocityTemplateEngine());
        get("/hello", (req, res) -> "Hello World");
    }

}
