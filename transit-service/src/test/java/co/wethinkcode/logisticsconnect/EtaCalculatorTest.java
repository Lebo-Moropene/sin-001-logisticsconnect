package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtaCalculatorTest {

    private final EtaCalculator calculator = new EtaCalculator();

    @Test
    void stageZeroUsesTheBaseWindow() {
        Map<String, Object> eta = calculator.estimate(0);

        assertEquals(24, eta.get("earliestHours"));
        assertEquals(48, eta.get("latestHours"));
        assertFalse((Boolean) eta.get("delayed"));
    }

    @Test
    void eachStageWidensTheWindowBySixHours() {
        Map<String, Object> eta = calculator.estimate(3);

        assertEquals(24 + 3 * 6, eta.get("earliestHours"));
        assertEquals(48 + 3 * 6, eta.get("latestHours"));
        assertTrue((Boolean) eta.get("delayed"));
    }

    @Test
    void maximumStageProducesTheWidestWindow() {
        Map<String, Object> eta = calculator.estimate(8);

        assertEquals(24 + 8 * 6, eta.get("earliestHours"));
        assertEquals(48 + 8 * 6, eta.get("latestHours"));
    }

    @Test
    void negativeStageIsClampedToZero() {
        Map<String, Object> eta = calculator.estimate(-4);

        assertEquals(24, eta.get("earliestHours"));
        assertFalse((Boolean) eta.get("delayed"));
    }

    @Test
    void stageAboveEightIsClampedToEight() {
        Map<String, Object> eta = calculator.estimate(99);

        assertEquals(24 + 8 * 6, eta.get("earliestHours"));
    }

    @Test
    void earliestInstantIsBeforeLatestInstant() {
        Map<String, Object> eta = calculator.estimate(2);

        Instant earliest = Instant.parse(
                (String) eta.get("earliest"));
        Instant latest = Instant.parse(
                (String) eta.get("latest"));

        assertTrue(earliest.isBefore(latest));
    }

    @Test
    void windowIsAlwaysInTheFuture() {
        Map<String, Object> eta = calculator.estimate(0);

        Instant earliest = Instant.parse(
                (String) eta.get("earliest"));

        assertTrue(earliest.isAfter(Instant.now()));
    }
}
