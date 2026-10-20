package com.roysolberg.java.hdlserver;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.OnStartupTriggeringPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.1.0 and earlier never deleted old logs, so they filled up the disk over the years.
 */
class LogCleanupTest {

    private static final String LOG_DIR_PROPERTY = "hdlserver.logDir";
    private static final String[] APPENDER_NAMES = {"HDL", "LOGFILE"};

    @TempDir
    Path logDirectory;

    @AfterEach
    void clearLogDirectory() {
        System.clearProperty(LOG_DIR_PROPERTY);
    }

    @Test
    void logsOlderThan30DaysAreDeletedOnRollover() throws IOException, URISyntaxException {
        Path oldBusLog = createLog("hdl-bus.2020-04-29-1.log", 400);
        Path oldServerLog = createLog("hdl-server.2020-04-29-1.log", 400);
        Path busLogFrom31DaysAgo = createLog("hdl-bus.2026-01-01-1.log", 31);
        Path recentBusLog = createLog("hdl-bus.2026-01-02-1.log", 29);
        Path recentServerLog = createLog("hdl-server.2026-01-02-1.log", 1);
        Path otherFile = createLog("other.2020-04-29-1.log", 400);
        Path currentBusLog = createLog("hdl-bus.log", 0);
        Path currentServerLog = createLog("hdl-server.log", 0);

        LoggerContext loggerContext = startLoggerContext();
        for (String appenderName : APPENDER_NAMES) {
            // As every midnight, and on startup when the current log was created before the server started
            loggerContext.getConfiguration().<RollingFileAppender>getAppender(appenderName).getManager().rollover();
        }
        loggerContext.getLogger("HDL").info("Started");
        assertTrue(loggerContext.stop(10, TimeUnit.SECONDS)); // Waits for the deletes, which run in the background

        assertFalse(Files.exists(oldBusLog));
        assertFalse(Files.exists(oldServerLog));
        assertFalse(Files.exists(busLogFrom31DaysAgo));
        assertTrue(Files.exists(recentBusLog));
        assertTrue(Files.exists(recentServerLog));
        assertTrue(Files.exists(otherFile), "Only the server's own logs are deleted");
        assertTrue(Files.exists(currentBusLog));
        assertTrue(Files.exists(currentServerLog));
        assertTrue(new String(Files.readAllBytes(currentBusLog), StandardCharsets.UTF_8).contains("Started"));
    }

    @Test
    void logsAlsoRollOverOnStartup() throws URISyntaxException {
        LoggerContext loggerContext = startLoggerContext();
        try {
            for (String appenderName : APPENDER_NAMES) {
                RollingFileAppender appender = loggerContext.getConfiguration().getAppender(appenderName);
                CompositeTriggeringPolicy policies = appender.getTriggeringPolicy();
                assertTrue(Arrays.stream(policies.getTriggeringPolicies()).anyMatch(OnStartupTriggeringPolicy.class::isInstance), appenderName);
            }
        } finally {
            loggerContext.stop();
        }
    }

    private LoggerContext startLoggerContext() throws URISyntaxException {
        System.setProperty(LOG_DIR_PROPERTY, logDirectory.toString());
        LoggerContext loggerContext = new LoggerContext("log-cleanup-test", null, getClass().getResource("/log4j2.properties").toURI());
        loggerContext.start();
        return loggerContext;
    }

    private Path createLog(String name, int daysOld) throws IOException {
        Path file = logDirectory.resolve(name);
        Files.write(file, ("Log line in " + name + "\n").getBytes(StandardCharsets.UTF_8));
        // An hour more than whole days, so a log from exactly 30 days ago isn't on the limit
        long modified = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysOld) - TimeUnit.HOURS.toMillis(1);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

}
