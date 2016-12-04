package com.roysolberg.java.hdlserver.hdl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Describing an action that can be performed in this application.
 */
public class Action implements Serializable {

    protected int id;
    protected String description;
    protected List<Command> commands;

    public Action(int id, String description) {
        this.id = id;
        this.description = description;
        commands = new ArrayList<>();
    }

    public void addCommand(Command command) {
        commands.add(command);
    }

    public String getDescription() {
        return description;
    }

    public List<Command> getCommands() {
        return commands;
    }

    public int getId() {
        return id;
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

}
