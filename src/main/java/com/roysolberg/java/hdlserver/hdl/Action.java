package com.roysolberg.java.hdlserver.hdl;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static spark.Spark.halt;

/**
 * Describing an action that can be performed in this application.
 */
public class Action implements Serializable {

    protected ActionType actionType;
    protected int id;
    protected String description;
    protected List<Command> commands;
    protected List<String> aliases;

    public Action(ActionType actionType, int id, String description) {
        this.actionType = actionType;
        this.id = id;
        this.description = description;
        commands = new ArrayList<>();
    }

    public void addCommand(Command command) {
        commands.add(command);
    }

    public void addAlias(String alias) {
        if (aliases == null) {
            aliases = new ArrayList<>();
        }
        aliases.add(alias);
    }

    public String getDescription() {
        return description;
    }

    public List<Command> getCommands() {
        return commands;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public int getId() {
        return id;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public static class Command implements Serializable {

        protected int subnetId;
        protected int deviceId;
        protected int operation;
        protected Integer parameter1;
        protected Integer parameter2;
        protected Integer parameter3;

        public Command(int subnetId, int deviceId, int operation, Integer parameter1, Integer parameter2, Integer parameter3) {
            this.subnetId = subnetId;
            this.deviceId = deviceId;
            this.operation = operation;
            this.parameter1 = parameter1;
            this.parameter2 = parameter2;
            this.parameter3 = parameter3;
        }

        public int getSubnetId() {
            return subnetId;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public int getOperation() {
            return operation;
        }

        public Integer getParameter1() {
            return parameter1;
        }

        public Integer getParameter2() {
            return parameter2;
        }

        public Integer getParameter3() {
            return parameter3;
        }
    }

    public String getUrl(String host) {
        switch (actionType) {
            case Dimmer:
                return String.format("http://%s/api/actions/dimmer/%s", host, description.toLowerCase().replaceAll(" ", "%20")); // TODO: URL encode
        }
        return String.format("http://%s/api/actions/%d", host, id);
    }

    public String getCurlCommand(String host, String authToken) {
        switch (actionType) {
            case Dimmer:
                return String.format("curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"%s\",\"value\":80}' http://%s/api/actions/dimmer/%s", authToken, host, description.toLowerCase().replaceAll(" ", "%20"));
        }
        return String.format("curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"%s\"}' http://%s/api/actions/%d", authToken, host, id);
    }

    public String getIftttCommand(String host, String authToken) {
        try {
            switch (actionType) {
                case Dimmer:
                    return String.format("Use <a href=\"https://ifttt.com/google_assistant\" target=\"_blank\">Google Assistant trigger</a> <code>Say a phrase with both a number and a text ingredient</code> and say <code>Dim the lights to # %% in $</code>, use <a href=\"https://ifttt.com/maker\">Maker action service</a> <code>Make a web request</code> with URL <code>http://&lt;fill in your external host name or IP address and port number&gt;/api/actions/dimmer/{{TextField}}</code>, method <code>POST</code>, content type <code>application/json</code> and body <code>{\"auth\":\"%s\",\"value\":{{NumberField}}}</code>. You need just one \"applet\" like this set up for all dimmer actions.", authToken);
            }
            return String.format("Use <a href=\"https://ifttt.com/google_assistant\" target=\"_blank\">Google Assistant trigger</a> <code>Say a simple phrase</code>, use <a href=\"https://ifttt.com/maker\">Maker action service</a> <code>Make a web request</code> with URL <code>http://&lt;fill in your external host name or IP address and port number&gt;/api/actions/%d</code>, method <code>POST</code>, content type <code>application/json</code> and body <code>{\"auth\":\"%s\"}</code>. You need one \"applet\" for each custom action.", id, authToken);
        } catch (Exception e) {
            System.err.print(e);
        }
        return "OK";
    }

    public enum ActionType implements Serializable {
        Custom, Dimmer, Relay
    }

}
