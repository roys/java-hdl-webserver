package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The starter that checks Java before starting the server.
 */
class ApplicationTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"1.8, 8", "9, 9", "11, 11", "17, 17", "21, 21", "25.0.1, 25", "abc, 0", "'', 0", "null, 0"})
    void majorVersion(String specificationVersion, int expected) {
        assertEquals(expected, Application.majorVersion(specificationVersion));
    }

    @Test
    void tooOldJavaSaysWhatToDo() {
        String message = Application.tooOldJavaMessage("2.0.0", "1.8.0_432");

        assertTrue(message.contains("needs Java 17 or later, but this is Java 1.8.0_432"));
        assertTrue(message.contains("sudo apt install openjdk-17-jre-headless"));
        assertTrue(message.contains("git checkout v1.1.1 -- releases/hdlserver-LATEST.jar"));
        assertTrue(message.contains("https://github.com/roys/java-hdl-webserver/releases/tag/v1.1.1"));
    }

}
