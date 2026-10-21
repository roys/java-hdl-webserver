package com.roysolberg.java.hdlserver.hdl;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describing an action that can be performed in this application.
 */
public class Action implements Serializable {

    // Pinned to what 1.0.x computed: hdlserver.db files hold serialized actions, so changing this would lose them.
    private static final long serialVersionUID = 4680743543381546225L;

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

        private static final long serialVersionUID = -193804117547559178L;

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
                return String.format("http://%s/api/actions/dimmer/%s", host, getEncodedAlias());
        }
        return String.format("http://%s/api/actions/%d", host, id);
    }

    public String getCurlCommand(String host, String authToken) {
        switch (actionType) {
            case Dimmer:
                return String.format("curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"%s\",\"value\":80}' http://%s/api/actions/dimmer/%s", authToken, host, getEncodedAlias());
        }
        return String.format("curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"%s\"}' http://%s/api/actions/%d", authToken, host, id);
    }

    protected String getEncodedAlias() {
        try {
            return URLEncoder.encode(description.toLowerCase(Locale.ROOT), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    public enum ActionType implements Serializable {
        Custom, Dimmer, Relay
    }

}
