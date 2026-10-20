package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.DimmerComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapdb.DB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upgrading must keep what earlier versions stored, since IFTTT applets use its auth key, action IDs and aliases.
 */
class LegacyDatabaseTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.0.3"})
    void readsEverythingEarlierVersionsStored(String version) {
        DB database = Application.openDatabase(LegacyDatabases.copyToDirectory(version, directory));
        try {
            Application application = new Application(database, null);

            assertEquals(LegacyDatabases.AUTH_TOKEN, application.configConcurrentMap.get(Application.AUTH_TOKEN_KEY));

            Action custom = (Action) application.actionsConcurrentMap.get(LegacyDatabases.CUSTOM_ACTION_ID);
            assertEquals(Action.ActionType.Custom, custom.getActionType());
            assertEquals("Open the garage", custom.getDescription());
            assertEquals(2, custom.getCommands().size());
            Action.Command panelControl = custom.getCommands().get(0);
            assertEquals(Arrays.asList(1, 81, 0xE3D8, 18, 1, null), Arrays.asList(panelControl.getSubnetId(), panelControl.getDeviceId(), panelControl.getOperation(), panelControl.getParameter1(), panelControl.getParameter2(), panelControl.getParameter3()));

            Action dimmer = (Action) application.actionsConcurrentMap.get(LegacyDatabases.DIMMER_ACTION_ID);
            assertEquals(Action.ActionType.Dimmer, dimmer.getActionType());
            assertEquals(Arrays.asList("kitchen"), dimmer.getAliases());
            assertEquals(Integer.valueOf(3), dimmer.getCommands().get(0).getParameter1());
            assertEquals(LegacyDatabases.DIMMER_ACTION_ID, application.actionAliasesConcurrentMap.get("the kitchen"));
            assertEquals(LegacyDatabases.DIMMER_ACTION_ID, application.actionAliasesConcurrentMap.get("kitchen"));

            Set<Class<?>> componentClasses = new HashSet<>();
            for (Object component : application.componentsConcurrentMap.values()) {
                componentClasses.add(component.getClass());
            }
            assertEquals(LegacyDatabases.NUM_OF_COMPONENTS, componentClasses.size());
            assertTrue(componentClasses.contains(DimmerComponent.class));
        } finally {
            database.close();
        }
    }

    @Test
    void keepsChangesAfterReopening() {
        String file = LegacyDatabases.copyToDirectory("1.0.3", directory);
        DB database = Application.openDatabase(file);
        new Application(database, null).removeAction(String.valueOf(LegacyDatabases.DIMMER_ACTION_ID));
        database.close();

        DB reopenedDatabase = Application.openDatabase(file);
        try {
            Application application = new Application(reopenedDatabase, null);
            assertNull(application.actionsConcurrentMap.get(LegacyDatabases.DIMMER_ACTION_ID));
            assertNull(application.actionAliasesConcurrentMap.get("kitchen"));
            assertNotNull(application.actionsConcurrentMap.get(LegacyDatabases.CUSTOM_ACTION_ID));
        } finally {
            reopenedDatabase.close();
        }
    }

    /**
     * Run with -Dhdlserver.legacyDb=/path/to/hdlserver.db to check a real database. It is copied first, never changed.
     */
    @Test
    void readsGivenDatabase() throws IOException {
        String givenFile = System.getProperty("hdlserver.legacyDb");
        assumeTrue(givenFile != null && !givenFile.isEmpty(), "No -Dhdlserver.legacyDb given");
        Path file = directory.resolve("hdlserver.db");
        Files.copy(Paths.get(givenFile), file);

        DB database = Application.openDatabase(file.toString());
        try {
            Application application = new Application(database, null);
            String authToken = (String) application.configConcurrentMap.get(Application.AUTH_TOKEN_KEY);
            assertTrue(authToken != null && authToken.length() >= Application.MIN_AUTH_TOKEN_LENGTH);
            for (Object action : application.actionsConcurrentMap.values()) {
                assertNotNull(((Action) action).getCommands());
            }
            int danglingAliases = 0; // Left by 1.0.x, which didn't always remove a deleted action's aliases
            for (Object actionId : application.actionAliasesConcurrentMap.values()) {
                if (application.actionsConcurrentMap.get(actionId) == null) {
                    danglingAliases++;
                }
            }
            for (Object component : application.componentsConcurrentMap.values()) {
                assertNotNull(((HdlComponent) component).getDescription());
            }
            System.out.printf("Read %d actions, %d aliases (%d for deleted actions) and %d components%n", application.actionsConcurrentMap.size(), application.actionAliasesConcurrentMap.size(), danglingAliases, application.componentsConcurrentMap.size());
        } finally {
            database.close();
        }
    }

}
