package com.roysolberg.java.hdlserver.hdl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionTest {

    @Test
    void dimmerUrlUsesTheEncodedAreaName() {
        Action action = new Action(Action.ActionType.Dimmer, 2, "The Kitchen & Stue");

        assertEquals("http://192.168.1.199:4567/api/actions/dimmer/the%20kitchen%20%26%20stue", action.getUrl("192.168.1.199:4567"));
    }

    @Test
    void customUrlUsesTheId() {
        Action action = new Action(Action.ActionType.Custom, 7, "Open the garage");

        assertEquals("http://192.168.1.199:4567/api/actions/7", action.getUrl("192.168.1.199:4567"));
    }

    @Test
    void curlCommand() {
        Action action = new Action(Action.ActionType.Dimmer, 2, "the kitchen");

        assertEquals("curl --include --header 'Content-Type: application/json' --request POST --data '{\"auth\":\"TOKEN\",\"value\":80}' http://host:4567/api/actions/dimmer/the%20kitchen", action.getCurlCommand("host:4567", "TOKEN"));
    }

}
