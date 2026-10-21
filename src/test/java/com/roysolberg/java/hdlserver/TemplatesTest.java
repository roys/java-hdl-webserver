package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.DimmerComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pages escape everything that comes from devices on the bus, from users and from requests.
 */
class TemplatesTest {

    private static final String SCRIPT = "<script>alert(1)</script>";
    private static final String QUOTE_BREAKOUT = "\"><img src=x onerror=alert(1)>";

    private final TemplateEngine templateEngine = TemplateEngine.createPrecompiled(ContentType.Html);

    @ParameterizedTest
    @ValueSource(strings = {"index", "actions", "security", "settings", "demo"})
    void everyPageRendersWithoutData(String name) {
        String html = render(new Page(name, "TOKEN", Collections.emptyList(), Collections.emptyList(), "192.168.1.199:4567", null, null));

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<li class=\"active\">"), "The page's own menu item is marked");
        assertTrue(html.trim().endsWith("</html>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index", "actions", "security", "settings", "demo"})
    void nothingFromDevicesUsersOrRequestsBecomesHtml(String name) {
        DimmerComponent dimmer = new DimmerComponent(1, 40, 600, SCRIPT + QUOTE_BREAKOUT, 2);
        Action action = new Action(Action.ActionType.Dimmer, 2, SCRIPT + QUOTE_BREAKOUT);
        action.addAlias(SCRIPT);
        action.addCommand(new Action.Command(1, 40, 0x0031, 1, null, null));
        List<HdlComponent> components = Collections.singletonList(dimmer);

        String html = render(new Page(name, QUOTE_BREAKOUT + SCRIPT, components, Collections.singletonList(action),
                "evil" + QUOTE_BREAKOUT, SCRIPT, QUOTE_BREAKOUT));

        assertFalse(html.contains(SCRIPT), name);
        assertFalse(html.contains("<img src=x"), name);
    }

    @Test
    void settingsShowTheKeyInTheField() {
        String html = render(new Page("settings", "KEY0123456789ABCDEFGHIJKLMN\"", Collections.emptyList(), Collections.emptyList(), "host", null, null));

        assertTrue(html.contains("value=\"KEY0123456789ABCDEFGHIJKLMN&#34;\""), html);
        assertTrue(html.contains("placeholder=\"At least 25 characters\" minlength=\"25\""));
    }

    @Test
    void messagesAreShown() {
        String html = render(new Page("index", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", "Saved & done", "Oops"));

        assertTrue(html.contains("alert-success"));
        assertTrue(html.contains("Saved &amp; done"));
        assertTrue(html.contains("alert-danger"));
        assertTrue(html.contains("Oops"));
    }

    @Test
    void actionsShowHowToCallThem() {
        Action dimmer = new Action(Action.ActionType.Dimmer, 2, "The Kitchen");
        dimmer.addAlias("Kitchen");
        dimmer.addAlias("Cooking");
        dimmer.addCommand(new Action.Command(1, 40, 0x0031, 3, null, null));
        Action custom = new Action(Action.ActionType.Custom, 7, "Open the garage");
        custom.addCommand(new Action.Command(1, 81, 0xE3D8, 18, 1, null));

        String html = render(new Page("actions", "TOKEN", Collections.emptyList(), Arrays.asList(dimmer, custom), "192.168.1.199:4567", null, null));

        assertTrue(html.contains("Dimmer - The Kitchen:"));
        assertTrue(html.contains("Kitchen, Cooking"));
        assertTrue(html.contains("http://192.168.1.199:4567/api/actions/dimmer/the%20kitchen"));
        assertTrue(html.contains("/api/actions/dimmer/{{TextField}}</code>"));
        assertTrue(html.contains("{&#34;auth&#34;:&#34;TOKEN&#34;,&#34;value&#34;:{{NumberField}}}") || html.contains("{\"auth\":\"TOKEN\",\"value\":{{NumberField}}}"));
        assertTrue(html.contains("Custom - Open the garage:"));
        assertTrue(html.contains("/api/actions/7</code>"));
        assertTrue(html.contains("Panel control"));
        assertTrue(html.contains("deleteActionModal-7"));
    }

    @Test
    void dimmerChannelsAreListed() {
        DimmerComponent dimmer = new DimmerComponent(1, 40, 600, "Kitchen", 3);

        String html = render(new Page("actions", "TOKEN", Collections.singletonList(dimmer), Collections.emptyList(), "host", null, null));

        assertTrue(html.contains("value=\"1:40:1\""));
        assertTrue(html.contains("value=\"1:40:3\""));
        assertFalse(html.contains("value=\"1:40:4\""));
    }

    private String render(Page page) {
        StringOutput output = new StringOutput();
        templateEngine.render(page.getName() + ".kte", page, output);
        return output.toString();
    }

}
