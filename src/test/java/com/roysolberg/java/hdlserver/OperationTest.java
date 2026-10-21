package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A command only reaches the bus with the values its operation takes.
 */
class OperationTest {

    @Test
    void validParametersBecomeNumbers() {
        assertEquals(Arrays.asList(1, 4, null), parse(Operation.SceneControl, "1", " 4 ", ""));
        assertEquals(Arrays.asList(3, 30, 300), parse(Operation.SingleChannelControl, "3", "30", "300"));
        assertEquals(Arrays.asList(3, 30, null), parse(Operation.SingleChannelControl, "3", "30", ""), "The run time is optional");
        assertEquals(Arrays.asList(5, 255, null), parse(Operation.UniversalSwitchControl, "5", "255", null));
        assertEquals(Arrays.asList(18, 1, 1), parse(Operation.PanelControl, "18", "1", "1"));
        assertEquals(Arrays.asList(1, 2, null), parse(Operation.CurtainSwitchControl, "1", "2", "9"), "A parameter the operation doesn't have is ignored");
    }

    @ParameterizedTest
    @CsvSource({
            "SceneControl, '', 4, '', Area is needed for scene control.",
            "SceneControl, 1, '', '', Scene number is needed for scene control.",
            "SceneControl, 0, 4, '', Area must be 1-255 for scene control.",
            "SceneControl, 1, 256, '', Scene number must be 0-255 for scene control.",
            "SceneControl, 1, -4, '', Scene number must be 0-255 for scene control.",
            "SceneControl, one, 4, '', Area must be 1-255 for scene control.",
            "SingleChannelControl, 3, 101, '', Level (0-100) must be 0-100 for single channel control.",
            "SingleChannelControl, 3, 30, 65536, Run time (seconds) must be 0-65535 for single channel control.",
            "UniversalSwitchControl, 5, 1, '', On (255) or off (0) must be 0 or 255 for universal switch control.",
            "CurtainSwitchControl, 1, 3, '', 'Stop (0), open (1) or close (2) must be 0-2 for curtain switch control.'",
            "PanelControl, 18, '', 1, Button number is needed for panel control.",
    })
    void otherParametersAreRefused(Operation operation, String parameter1, String parameter2, String parameter3, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> operation.parse(Arrays.asList(parameter1, parameter2, parameter3)));

        assertEquals(message, failure.getMessage());
    }

    @Test
    void everyOperationTellsThePageItsParameters() {
        assertEquals("[{\"name\":\"Area\",\"min\":1,\"max\":255,\"required\":true},{\"name\":\"Scene number\",\"min\":0,\"max\":255,\"required\":true},null]",
                Operation.SceneControl.getParametersJson());
        for (Operation operation : Operation.values()) {
            assertEquals(3, operation.getParameters().size(), operation.name());
            assertNotNull(operation.getParameters().get(0), operation.name() + " uses parameter 1");
        }
    }

    private static List<Integer> parse(Operation operation, String... parameters) {
        return operation.parse(Arrays.asList(parameters));
    }
}
