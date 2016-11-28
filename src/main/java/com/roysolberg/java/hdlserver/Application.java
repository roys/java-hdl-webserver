package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.service.HdlService;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

import java.util.HashMap;
import java.util.Map;

import static spark.Spark.get;
import static spark.Spark.staticFiles;

public class Application {

    private static final String TAG = Application.class.getSimpleName();

    protected static HdlService hdlService;

    public static void main(String[] args) {
        if (false) {
            staticFiles.location("/public");
            staticFiles.expireTime(600);
        } else {
            staticFiles.externalLocation(System.getProperty("user.dir") + "/src/main/resources/public");
            System.out.println("System.getProperty(\"user.dir\"):" + System.getProperty("user.dir"));
        }
        setUpHdlService();
        setUpPaths();
    }

    protected static void setUpHdlService() {
        hdlService = new HdlService();
    }

    protected static void setUpPaths() {
        get("/", (req, res) -> {
            System.out.println("IP:" + req.ip());
            Map<String, Object> model = new HashMap<>();
            model.put("hello", "Velocity World");

            // The wm files are located under the resources directory
            return new ModelAndView(model, "templates/index.vm");
        }, new VelocityTemplateEngine());
        get("/hello", (req, res) -> "Hello World");
    }

}
