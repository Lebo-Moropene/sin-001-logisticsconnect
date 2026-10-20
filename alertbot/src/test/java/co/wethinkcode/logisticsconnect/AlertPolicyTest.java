package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertPolicyTest {

    private final AlertPolicy policy = new AlertPolicy();

    @Test
    void defaultThresholdIsFive() {
        assertEquals(5, policy.threshold());
    }

    @Test
    void firstSightingAtThresholdAlerts() {
        assertTrue(policy.shouldAlert(null, 5));
    }

    @Test
    void firstSightingBelowThresholdDoesNotAlert() {
        assertFalse(policy.shouldAlert(null, 4));
    }

    @Test
    void crossingFromBelowAlerts() {
        assertTrue(policy.shouldAlert(4, 5));
    }

    @Test
    void stayingAboveThresholdDoesNotReAlert() {
        assertFalse(policy.shouldAlert(5, 6));
        assertFalse(policy.shouldAlert(7, 5));
    }

    @Test
    void droppingBelowThresholdDoesNotAlert() {
        assertFalse(policy.shouldAlert(7, 3));
    }

    @Test
    void risingAgainAfterDroppingAlerts() {
        assertTrue(policy.shouldAlert(3, 5));
    }

    @Test
    void customThresholdIsHonoured() {
        AlertPolicy strict = new AlertPolicy(2);

        assertTrue(strict.shouldAlert(null, 2));
        assertFalse(strict.shouldAlert(null, 1));
        assertEquals(2, strict.threshold());
    }

    @Test
    void negativeThresholdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlertPolicy(-1));
    }
}
