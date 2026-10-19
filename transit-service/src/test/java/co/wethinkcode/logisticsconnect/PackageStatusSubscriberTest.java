package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackageStatusSubscriberTest {

    private final DelayStageCache cache = new DelayStageCache();

    private final PackageStatusSubscriber subscriber =
            new PackageStatusSubscriber(cache);

    @Test
    void validMessageUpdatesCache() throws Exception {
        subscriber.handle(
                "{\"hubId\":\"H-501\",\"stage\":5,"
                        + "\"timestamp\":\"2026-07-18T10:15:00Z\"}");

        assertEquals(5, cache.find("H-501"));
        assertEquals(1, subscriber.receivedCount());
    }

    @Test
    void messageWithoutStageIsIgnored() throws Exception {
        subscriber.handle("{\"hubId\":\"H-501\"}");

        assertNull(cache.find("H-501"));
        assertEquals(0, subscriber.receivedCount());
    }

    @Test
    void nonNumericStageIsIgnored() throws Exception {
        subscriber.handle(
                "{\"hubId\":\"H-501\",\"stage\":\"bad\"}");

        assertNull(cache.find("H-501"));
        assertEquals(0, subscriber.receivedCount());
    }

    @Test
    void messageWithoutHubIdIsIgnored() throws Exception {
        subscriber.handle("{\"stage\":4}");

        assertEquals(0, cache.size());
        assertEquals(0, subscriber.receivedCount());
    }
}
