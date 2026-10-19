package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DelayStageCacheTest {

    private final DelayStageCache cache = new DelayStageCache();

    @Test
    void storesAndReturnsStageForHub() {
        cache.update("H-501", 3);

        assertEquals(3, cache.find("H-501"));
    }

    @Test
    void hubIdsAreMatchedRegardlessOfCaseAndPadding() {
        cache.update("h-501 ", 3);

        assertEquals(3, cache.find(" H-501"));
    }

    @Test
    void newestStageWins() {
        cache.update("H-501", 3);
        cache.update("H-501", 7);

        assertEquals(7, cache.find("H-501"));
        assertEquals(1, cache.size());
    }

    @Test
    void unknownHubReturnsNull() {
        assertNull(cache.find("H-999"));
    }

    @Test
    void blankHubIdIsIgnored() {
        cache.update("  ", 3);

        assertEquals(0, cache.size());
    }

    @Test
    void nullHubIdIsIgnored() {
        cache.update(null, 3);

        assertEquals(0, cache.size());
    }
}
