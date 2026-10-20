package co.wethinkcode.logisticsconnect;

/**
 * Decides when a delay stage warrants an alert.
 *
 * AlertBot fires only on a crossing: the new stage is at or above the
 * threshold and the hub's previous stage was below it (or unknown).
 * A hub already above the threshold does not re-alert on every update.
 */
public class AlertPolicy {

    public static final int DEFAULT_THRESHOLD = 5;

    private final int threshold;

    public AlertPolicy() {
        this(DEFAULT_THRESHOLD);
    }

    public AlertPolicy(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException(
                    "Threshold cannot be negative");
        }

        this.threshold = threshold;
    }

    public int threshold() {
        return threshold;
    }

    /**
     * @param previousStage last stage seen for the hub, or null if the
     *                      topic has not mentioned it yet
     * @param newStage      stage arriving in the current message
     */
    public boolean shouldAlert(Integer previousStage, int newStage) {
        if (newStage < threshold) {
            return false;
        }

        return previousStage == null || previousStage < threshold;
    }
}
