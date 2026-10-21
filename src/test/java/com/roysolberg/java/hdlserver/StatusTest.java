package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class StatusTest {

    private static final Instant NOW = Instant.parse("2026-10-21T20:00:00Z");

    @Test
    void durationsAreDescribedInWords() {
        assertEquals("0 seconds", Status.Companion.describe(Duration.ZERO));
        assertEquals("1 second", Status.Companion.describe(Duration.ofSeconds(1)));
        assertEquals("12 minutes, 5 seconds", Status.Companion.describe(Duration.ofSeconds(725)));
        assertEquals("3 days, 4 hours", Status.Companion.describe(Duration.ofDays(3).plusHours(4).plusMinutes(5)));
    }

    @Test
    void theBusIsAliveWhenItSaidSomethingInTheLastMinute() {
        assertTrue(status(NOW.minusSeconds(5)).getBusAlive());
        assertFalse(status(NOW.minusSeconds(90)).getBusAlive());
        assertFalse(status(null).getBusAlive());
        assertEquals("never", status(null).getLastBusMessageAgo());
    }

    @Test
    void theGraphUsesDecimalPointsWhateverTheLanguage() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("nb-NO"));
        try {
            String line = status(NOW).getSparklineLine();
            assertTrue(line.matches("[0-9., ]+"), line);
            assertEquals(60, line.split(" ").length);
            assertTrue(status(NOW).getSparklineArea().startsWith("0," + Status.SPARKLINE_HEIGHT + " "));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    private static Status status(Instant lastBusMessage) {
        Integer[] perMinute = new Integer[60];
        Arrays.fill(perMinute, 3);
        perMinute[59] = 7;
        return new Status("2.0.0", "21", NOW.minus(Duration.ofHours(2)), NOW, 1000, lastBusMessage, Arrays.asList(perMinute),
                107, 24, null, 0, 120, 512, null);
    }

}
