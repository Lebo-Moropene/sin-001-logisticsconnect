package co.wethinkcode.logisticsconnect;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The latest delay stage per hub, fed by the package-status-topic
 * subscription. hub-service and delay-stage-service stay the source
 * of truth; this is only the copy transit-service has been told about.
 */
public class DelayStageCache {

    private final Map<String, Integer> stages =
            new ConcurrentHashMap<>();

    /**
     * Records the newest stage for a hub. Blank ids are ignored.
     */
    public void update(String hubId, int stage) {
        if (hubId == null || hubId.isBlank()) {
            return;
        }

        stages.put(normalize(hubId), stage);
    }

    /**
     * Returns the last stage seen for a hub, or null when the topic
     * has not carried an update for it yet.
     */
    public Integer find(String hubId) {
        if (hubId == null) {
            return null;
        }

        return stages.get(normalize(hubId));
    }

    public int size() {
        return stages.size();
    }

    private static String normalize(String hubId) {
        return hubId.trim().toUpperCase(Locale.ROOT);
    }
}
