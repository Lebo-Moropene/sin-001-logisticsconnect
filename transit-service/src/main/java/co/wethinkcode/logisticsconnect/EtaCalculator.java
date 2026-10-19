package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Turns a delay stage into an estimated arrival window.
 *
 * Without any delay a parcel is expected within 24-48 hours of collection.
 * Every delay stage widens the window by {@link #HOURS_PER_DELAY_STAGE}
 * hours at both ends, so a stage 8 hub lands much later.
 */
public class EtaCalculator {

    public static final int BASE_MIN_HOURS = 24;

    public static final int BASE_MAX_HOURS = 48;

    public static final int HOURS_PER_DELAY_STAGE = 6;

    public static final int MAX_STAGE = 8;

    /**
     * Returns the arrival window for the given delay stage.
     */
    public Map<String, Object> estimate(int delayStage) {
        int stage = normalizeStage(delayStage);

        int minHours = BASE_MIN_HOURS
                + stage * HOURS_PER_DELAY_STAGE;
        int maxHours = BASE_MAX_HOURS
                + stage * HOURS_PER_DELAY_STAGE;

        Instant now = Instant.now();

        return Map.of(
                "earliestHours", minHours,
                "latestHours", maxHours,
                "earliest", plusHours(now, minHours),
                "latest", plusHours(now, maxHours),
                "delayed", stage > 0
        );
    }

    /**
     * Stages outside 0-8 are treated as the nearest bound so a bad
     * upstream value can never produce a negative window.
     */
    private static int normalizeStage(int delayStage) {
        return Math.max(0, Math.min(MAX_STAGE, delayStage));
    }

    private static String plusHours(Instant from, int hours) {
        return from.plus(hours, ChronoUnit.HOURS).toString();
    }
}
