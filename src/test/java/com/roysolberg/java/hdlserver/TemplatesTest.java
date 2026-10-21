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
import java.util.HashMap;
import java.util.Map;
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
        assertTrue(html.contains("is-active\" href=\"" + (name.equals("index") ? "/" : "/" + name) + "\" aria-current=\"page\""), "The page's own menu item is marked");
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

        assertTrue(html.contains("notification is-success"));
        assertTrue(html.contains("Saved &amp; done"));
        assertTrue(html.contains("notification is-danger"));
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

        assertTrue(html.contains("The Kitchen <span class=\"tag is-rounded\">Dimmer</span>"));
        assertTrue(html.contains("Kitchen, Cooking"));
        assertTrue(html.contains("http://192.168.1.199:4567/api/actions/dimmer/the%20kitchen"));
        assertTrue(html.contains("can't pass on a level"));
        assertTrue(html.contains("{\"auth\":\"TOKEN\",\"value\":&lt;0-100&gt;}"));
        assertTrue(html.contains("Open the garage <span class=\"tag is-rounded\">Custom</span>"));
        assertTrue(html.contains("/api/actions/7</code>"));
        assertTrue(html.contains("<code>Activate scene</code>"), "IFTTT's only Google Assistant trigger since 2022");
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

    @Test
    void activityShowsEntriesWithoutRunningAnyOfThem() {
        ActivityLog activityLog = new ActivityLog();
        activityLog.record("API (203.0.113.9)", "Dim \"" + SCRIPT + QUOTE_BREAKOUT + "\"", "Refused: wrong key", false);
        activityLog.record("Web page (192.168.1.3)", "Open the garage", "Sent", true);
        Page page = new Page("activity", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", null, null);

        String html = render("activity", page, "entries", activityLog.entries());

        assertTrue(html.contains("Open the garage"));
        assertTrue(html.contains("tag is-success"));
        assertTrue(html.contains("tag is-danger"));
        assertTrue(html.contains("href=\"/activity\" aria-current=\"page\""));
        assertTrue(html.contains("data-live=\"/activity/events\""));
        assertFalse(html.contains(SCRIPT));
        assertFalse(html.contains("<img src=x"));
    }

    @Test
    void activityWithNothingYet() {
        Page page = new Page("activity", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", null, null);

        assertTrue(render("activity", page, "entries", Collections.emptyList()).contains("Nothing yet"));
    }

    @Test
    void statusShowsTheServerAndTheBus() {
        Page page = new Page("status", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", null, null);
        java.time.Instant now = java.time.Instant.now();
        ActivityLog activityLog = new ActivityLog();
        activityLog.record("API (203.0.113.9)", "Open the garage", "Sent", true);
        Status status = new Status("2.0.0", "21.0.4", now.minusSeconds(3 * 86400 + 7200), now, 12345, now.minusSeconds(2),
                Collections.nCopies(60, 4), 107, 24, activityLog.entries().get(0), 3, 120, 512, 57.4);

        String html = render("status", page, "status", status);

        assertTrue(html.contains("Alive"));
        assertTrue(html.contains("3 days, 2 hours"));
        assertTrue(html.contains(">107<"));
        assertTrue(html.contains("<polyline points=\"0.0,"));
        assertTrue(html.contains("57 &deg;C"));
        assertTrue(html.contains("value=\"120\" max=\"512\""), "Memory bar");
        assertTrue(html.contains("data-live=\"/status/events\""));
    }

    @Test
    void statusWithASilentBusAndNoCalls() {
        Page page = new Page("status", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", null, null);
        java.time.Instant now = java.time.Instant.now();
        Status status = new Status("2.0.0", "21.0.4", now, now, 0, null, Collections.nCopies(60, 0), 0, 0, null, 0, 10, 512, null);

        String html = render("status", page, "status", status);

        assertTrue(html.contains("Silent"));
        assertTrue(html.contains("None since the server started"));
        assertFalse(html.contains("CPU temperature"));
    }

    @Test
    void customActionsAskBeforeRunningAndDimmersTakeALevel() {
        Action dimmer = new Action(Action.ActionType.Dimmer, 2, "The Kitchen");
        Action custom = new Action(Action.ActionType.Custom, 7, "Open the garage");

        String html = render(new Page("actions", "TOKEN", Collections.emptyList(), Arrays.asList(dimmer, custom), "host", null, null));

        assertTrue(html.contains("data-open=\"runActionModal-7\""));
        assertTrue(html.contains("<dialog id=\"runActionModal-7\""));
        assertTrue(html.contains("Run &#34;Open the garage&#34; now?") || html.contains("Run \"Open the garage\" now?"));
        assertFalse(html.contains("runActionModal-2"), "No confirmation for dimmers");
        assertTrue(html.contains("name=\"value\" class=\"input level-input\" min=\"0\" max=\"100\""));
    }

    /**
     * The pages get a Content-Security-Policy that blocks inline styles and scripts, so anything like that would break.
     */
    @ParameterizedTest
    @ValueSource(strings = {"index", "actions", "security", "settings", "demo", "activity", "status"})
    void pagesHaveNoInlineStylesOrScripts(String name) {
        Action action = new Action(Action.ActionType.Custom, 7, "Open the garage");
        action.addCommand(new Action.Command(1, 81, 0xE3D8, 18, 1, null));
        Page page = new Page(name, "TOKEN", Collections.singletonList(new DimmerComponent(1, 40, 600, "Kitchen", 2)),
                Collections.singletonList(action), "host", "Saved", "Oops");
        ActivityLog activityLog = new ActivityLog();
        activityLog.record("API (203.0.113.9)", "Open the garage", "Sent", true);
        java.time.Instant now = java.time.Instant.now();
        Status status = new Status("2.0.0", "21.0.4", now, now, 1, now, Collections.nCopies(60, 1), 1, 1, null, 0, 10, 512, 50.0);
        Map<String, Object> params = new HashMap<>();
        params.put("page", page);
        params.put("entries", activityLog.entries());
        params.put("status", status);
        StringOutput output = new StringOutput();
        templateEngine.render(name + ".kte", params, output);
        String html = output.toString();

        assertFalse(html.contains("style="), "Inline style on " + name);
        assertFalse(html.contains("<style"), "Style element on " + name);
        assertFalse(java.util.regex.Pattern.compile("\\son[a-z]+=").matcher(html).find(), "Event handler attribute on " + name);
        assertEquals(1, html.split("<script").length - 1, "Only app.js on " + name);
        assertTrue(html.contains("<script src=\"/assets/app.js\" defer></script>"));
    }

    @Test
    void commandsOfferTheOperationsWithTheirParameters() {
        String html = render(new Page("actions", "TOKEN", Collections.emptyList(), Collections.emptyList(), "host", null, null));

        for (Operation operation : Operation.values()) {
            assertTrue(html.contains("<option value=\"" + operation.getCode() + "\" data-parameters=\""), operation.name());
        }
        assertTrue(html.contains("Scene control (0x0002)"));
        assertTrue(html.contains("data-parameters=\"Area|Scene number|\""));
        assertTrue(html.contains("<label class=\"label is-small\">Area</label>"), "Named for the first operation");
        assertTrue(html.contains("data-parameter=\"3\" hidden"), "Scene control has no third parameter");
    }

    @Test
    void modulesThatHaveNotAnsweredCanBeRemoved() {
        DimmerComponent answering = new DimmerComponent(1, 40, 600, "Kitchen", 2);
        DimmerComponent gone = new DimmerComponent(1, 41, 600, "Old dimmer", 2);
        Page page = new Page("index", "TOKEN", Arrays.asList(answering, gone), Collections.emptyList(), "host", null, null);

        String html = render("index", page, "answered", Collections.singleton("1-40-600"));

        assertEquals(1, html.split("No answer</span>").length - 1, "Only the one that hasn't answered");
        assertTrue(html.indexOf("No answer</span>") > html.indexOf("Old dimmer"));
        assertTrue(html.contains("data-open=\"removeComponent-1-41-600\""));
        assertTrue(html.contains("<input type=\"hidden\" name=\"componentId\" value=\"1-41-600\"/>"));
        assertTrue(html.contains("Remove Old dimmer (1/41)?"));
    }

    /** Like the server does it: with the parameters by name, so the template's other parameters get their defaults. */
    private String render(Page page) {
        StringOutput output = new StringOutput();
        templateEngine.render(page.getName() + ".kte", Collections.singletonMap("page", page), output);
        return output.toString();
    }

    private String render(String name, Page page, String extraName, Object extra) {
        Map<String, Object> params = new HashMap<>();
        params.put("page", page);
        params.put(extraName, extra);
        StringOutput output = new StringOutput();
        templateEngine.render(name + ".kte", params, output);
        return output.toString();
    }

}
