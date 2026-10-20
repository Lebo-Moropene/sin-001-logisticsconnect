package co.wethinkcode.logisticsconnect;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The latest delay stage per hub, fed by the package-status-topic
 * subscription. AlertBot needs the previous stage to tell a fresh
 * crossing from a hub that has been delayed for a while.
 */
public class DelayStageCache {

    private final Map<String, Integer> stages =
            new ConcurrentHashMap<>();

    public void update(String hubId, int stage) {
        if (hubId == null || hubId.isBlank()) {
            return;
        }

        stages.put(normalize(hubId), stage);
    }

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
