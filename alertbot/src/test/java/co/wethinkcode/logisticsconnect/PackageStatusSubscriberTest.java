package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageStatusSubscriberTest {

    private DelayStageCache cache;
    private SocialAlertFeed feed;
    private PackageStatusSubscriber subscriber;

    @BeforeEach
    void setUp() {
        cache = new DelayStageCache();
        feed = new SocialAlertFeed();
        subscriber = new PackageStatusSubscriber(
                cache, new AlertPolicy(), feed);
    }

    private void send(String hubId, Integer stage) throws Exception {
        String json = stage == null
                ? "{\"hubId\":\"" + hubId + "\"}"
                : "{\"hubId\":\"" + hubId + "\",\"stage\":" + stage
                        + ",\"timestamp\":\"2026-07-18T10:15:00Z\"}";

        subscriber.handle(json);
    }

    @Test
    void crossingThresholdPostsOneAlert() throws Exception {
        send("H-501", 5);

        List<Map<String, Object>> posts = feed.recent();

        assertEquals(1, posts.size());
        assertEquals("H-501", posts.get(0).get("hubId"));
        assertEquals(5, posts.get(0).get("stage"));
        assertEquals(SocialAlertFeed.ACCOUNT,
                posts.get(0).get("account"));
        assertEquals(1, subscriber.alertCount());
        assertEquals(5, cache.find("H-501"));
    }

    @Test
    void stageBelowThresholdPostsNothing() throws Exception {
        send("H-501", 4);

        assertTrue(feed.recent().isEmpty());
        assertEquals(0, subscriber.alertCount());
        assertEquals(1, subscriber.receivedCount());
    }

    @Test
    void repeatedHighStageDoesNotSpamTheFeed() throws Exception {
        send("H-501", 5);
        send("H-501", 6);
        send("H-501", 8);

        assertEquals(1, feed.recent().size());
        assertEquals(1, subscriber.alertCount());
        assertEquals(3, subscriber.receivedCount());
    }

    @Test
    void droppingAndRisingAgainPostsASecondAlert() throws Exception {
        send("H-501", 5);
        send("H-501", 3);
        send("H-501", 5);

        assertEquals(2, feed.recent().size());
        assertEquals(2, subscriber.alertCount());
    }

    @Test
    void messageWithoutStageIsIgnored() throws Exception {
        send("H-501", null);

        assertTrue(feed.recent().isEmpty());
        assertEquals(0, subscriber.receivedCount());
    }

    @Test
    void malformedJsonIsRejectedWithoutPosting() {
        // The JMS listener catches this and drops the message.
        assertThrows(JsonProcessingException.class,
                () -> subscriber.handle("not json at all"));

        assertTrue(feed.recent().isEmpty());
        assertEquals(0, subscriber.receivedCount());
    }
}
