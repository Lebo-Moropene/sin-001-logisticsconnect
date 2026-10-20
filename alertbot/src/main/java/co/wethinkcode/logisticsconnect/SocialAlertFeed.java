package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulated outbound social media account. Instead of calling a real
 * posting API, every alert is logged as the outgoing post and kept in
 * an in-memory feed that /alerts can serve back for verification.
 */
public class SocialAlertFeed {

    public static final String ACCOUNT =
            "@LogisticsConnectAlerts";

    private final List<Map<String, Object>> posts =
            new ArrayList<>();

    /**
     * Publishes one alert and returns the post as it would have been
     * sent.
     */
    public synchronized Map<String, Object> post(
            String hubId, int stage) {

        String message = "Delay stage " + stage + " at hub "
                + hubId + " — deliveries through this hub are "
                + "expected to be delayed. Check before you ship.";

        Map<String, Object> post = new LinkedHashMap<>();
        post.put("platform", "simulated-social");
        post.put("account", ACCOUNT);
        post.put("hubId", hubId);
        post.put("stage", stage);
        post.put("message", message);
        post.put("postedAt", Instant.now().toString());

        posts.add(post);

        System.out.println(
                "[SIMULATED SOCIAL POST] " + ACCOUNT + ": "
                        + message);

        return post;
    }

    /**
     * Everything posted so far, oldest first.
     */
    public synchronized List<Map<String, Object>> recent() {
        return List.copyOf(posts);
    }
}
