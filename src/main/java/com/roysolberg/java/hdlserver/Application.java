package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.service.HdlService;
import org.mapdb.DB;
import org.mapdb.DBException;
import org.mapdb.DBMaker;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

import java.math.BigInteger;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static spark.Spark.get;
import static spark.Spark.staticFiles;

public class Application {

    private static final String TAG = Application.class.getSimpleName();

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
        if (false) {
            staticFiles.location("/public");
            staticFiles.expireTime(600);
        } else {
            staticFiles.externalLocation(System.getProperty("user.dir") + "/src/main/resources/public");
            System.out.println("System.getProperty(\"user.dir\"):" + System.getProperty("user.dir"));
        }
    }

    protected void setUpPaths() {
        get("/", (req, res) -> {
            System.out.println("IP:" + req.ip());
            InetAddress remoteInetAddress = InetAddress.getByName(req.ip());
            System.out.println("remoteInetAddress.isSiteLocalAddress():" + remoteInetAddress.isSiteLocalAddress());
            System.out.println("remoteInetAddress.getHostAddress():" + remoteInetAddress.getHostAddress());
            System.out.println("remoteInetAddress:" + remoteInetAddress);
            Map<String, Object> model = new HashMap<>();
            model.put("hello", "Velocity World");
            model.put("config", configConcurrentMap);

            // The wm files are located under the resources directory
            return new ModelAndView(model, "templates/index.vm");
        }, new VelocityTemplateEngine());
        get("/hello", (req, res) -> "Hello World");
    }

}
