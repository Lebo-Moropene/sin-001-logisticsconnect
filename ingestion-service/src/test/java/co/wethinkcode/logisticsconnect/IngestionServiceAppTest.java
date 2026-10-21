package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link IngestionServiceApp} cleaning and de-duplication rules.
 *
 * <p>These exercise the static helpers directly (no HTTP, no classpath resource) so
 * the messy-CSV rules stay verifiable as the service evolves.</p>
 */
class IngestionServiceAppTest {

    /** Trimming, whitespace collapsing and placeholder handling. */
    @Test
    void cleanFieldTrimsCollapsesAndBlanksPlaceholders() {
        assertEquals("H-502", IngestionServiceApp.cleanField(" H-502 "));
        assertEquals("Cape Town Port",
                IngestionServiceApp.cleanField("Cape Town  Port"));
        assertEquals("", IngestionServiceApp.cleanField("N/A"));
        assertEquals("", IngestionServiceApp.cleanField("unknown"));
        assertEquals("", IngestionServiceApp.cleanField("  "));
        assertEquals("", IngestionServiceApp.cleanField(null));
    }

    /** Hub IDs are trimmed and upper-cased. */
    @Test
    void normalizeHubIdUpperCasesAndTrims() {
        assertEquals("H-501", IngestionServiceApp.normalizeHubId(" h-501 "));
        assertEquals("H-501", IngestionServiceApp.normalizeHubId("H-501"));
    }

    /** Provinces are mapped to their official spelling regardless of input form. */
    @Test
    void normalizeProvinceUsesOfficialSpelling() {
        assertEquals("Gauteng", IngestionServiceApp.normalizeProvince("gauteng"));
        assertEquals("Western Cape",
                IngestionServiceApp.normalizeProvince("western cape"));
        assertEquals("KwaZulu-Natal",
                IngestionServiceApp.normalizeProvince("Kwa-Zulu Natal"));
        assertEquals("KwaZulu-Natal",
                IngestionServiceApp.normalizeProvince("kwazulu natal"));
        assertEquals("", IngestionServiceApp.normalizeProvince("N/A"));
    }

    /** Every documented boolean encoding collapses to one of three values. */
    @Test
    void parseBooleanNormalizesEncodings() {
        assertTrue(IngestionServiceApp.parseBoolean("Y"));
        assertTrue(IngestionServiceApp.parseBoolean("yes"));
        assertTrue(IngestionServiceApp.parseBoolean("1"));
        assertTrue(IngestionServiceApp.parseBoolean("TRUE"));

        assertFalse(IngestionServiceApp.parseBoolean("N"));
        assertFalse(IngestionServiceApp.parseBoolean("no"));
        assertFalse(IngestionServiceApp.parseBoolean("0"));
        assertFalse(IngestionServiceApp.parseBoolean("FALSE"));

        assertNull(IngestionServiceApp.parseBoolean("unknown"));
        assertNull(IngestionServiceApp.parseBoolean("N/A"));
    }

    /** Duplicate hubs collapse to one record, keeping every folded-in ID. */
    @Test
    void buildHubRecordsDeduplicatesOnProvinceAndCenter() {
        List<Map<String, Object>> hubs = IngestionServiceApp.buildHubRecords(List.of(
                new String[]{"H-500", "Gauteng", "Johannesburg Central", "Y"},
                new String[]{"H-504", "Gauteng", "Johannesburg Central", "true"},
                new String[]{"H-510", "Gauteng", "johannesburg central", "FALSE"},
                new String[]{"H-515", "Gauteng", "Johannesburg Central", "YES"}
        ));

        assertEquals(1, hubs.size());

        Map<String, Object> hub = hubs.get(0);
        assertEquals("H-500", hub.get("hub_id"));
        assertEquals("Johannesburg Central", hub.get("sorting_center"));
        assertEquals(true, hub.get("active"),
                "conflicting known flags resolve to active (fail-safe)");

        @SuppressWarnings("unchecked")
        List<String> duplicateIds = (List<String>) hub.get("duplicate_ids");
        assertEquals(List.of("H-500", "H-504", "H-510", "H-515"), duplicateIds);
    }

    /** A blank province is recovered from another row for the same sorting center. */
    @Test
    void buildHubRecordsInfersMissingProvinceFromSameCenter() {
        List<Map<String, Object>> hubs = IngestionServiceApp.buildHubRecords(List.of(
                new String[]{"H-506", "Kwa-Zulu Natal", "Durban Harbour", "1"},
                new String[]{"H-516", "", "Durban  Harbour", "true"}
        ));

        assertEquals(1, hubs.size());
        assertEquals("KwaZulu-Natal", hubs.get(0).get("province"));
        assertEquals("Durban Harbour", hubs.get(0).get("sorting_center"));
    }

    /** Unrecoverable provinces fall back to a visible placeholder, not a dropped row. */
    @Test
    void buildHubRecordsUsesUnknownProvinceWhenNothingToInferFrom() {
        List<Map<String, Object>> hubs = IngestionServiceApp.buildHubRecords(List.<String[]>of(
                new String[]{"H-900", "", "Atlantis Depot", "Y"}
        ));

        assertEquals(1, hubs.size());
        assertEquals(IngestionServiceApp.UNKNOWN_PROVINCE,
                hubs.get(0).get("province"));
    }

    /** Rows without an ID or sorting center are skipped rather than emitted broken. */
    @Test
    void buildHubRecordsSkipsRowsMissingIdentity() {
        List<Map<String, Object>> hubs = IngestionServiceApp.buildHubRecords(List.of(
                new String[]{"", "Gauteng", "Nowhere", "Y"},
                new String[]{"H-901", "Gauteng", "", "Y"},
                new String[]{"H-902", "Gauteng", "Somewhere", "Y"}
        ));

        assertEquals(1, hubs.size());
        assertEquals("H-902", hubs.get(0).get("hub_id"));
    }

    /** Unknown status is preserved as null rather than guessed to true/false. */
    @Test
    void buildHubRecordsKeepsUnknownStatusAsNull() {
        List<Map<String, Object>> hubs = IngestionServiceApp.buildHubRecords(List.of(
                new String[]{"H-511", "Limpopo", "Polokwane Hub", "unknown"},
                new String[]{"H-517", "Northern Cape", "Kimberley Hub", "N/A"}
        ));

        assertEquals(2, hubs.size());
        assertNull(hubs.get(0).get("active"));
        assertNull(hubs.get(1).get("active"));
    }
}
