package co.wethinkcode.logisticsconnect;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LogisticsConnect ingestion service (port 7050).
 *
 * <p>Parses {@code hubs-global.csv} — a deliberately messy legacy export of hubs,
 * sorting centers and regional districts — cleans it, deduplicates it, and exposes
 * the result over REST so the other services (starting with hub-service) can consume
 * it instead of re-implementing the cleaning themselves.</p>
 *
 * <h2>Cleaning rules</h2>
 * <ul>
 *   <li><b>Padding / whitespace:</b> every field is trimmed and internal runs of
 *       whitespace are collapsed to a single space.</li>
 *   <li><b>Casing:</b> hub IDs are upper-cased ({@code h-501} → {@code H-501});
 *       names are title-cased; provinces are mapped to their official spelling.</li>
 *   <li><b>Placeholders:</b> {@code N/A}, {@code TBD}, {@code unknown}, {@code -},
 *       {@code NaN} (any case) are treated as missing rather than kept verbatim.</li>
 *   <li><b>Booleans:</b> {@code Y/yes/1/true} → {@code true}, {@code N/no/0/false}
 *       → {@code false}; anything else (e.g. {@code unknown}) becomes {@code null}
 *       i.e. "unknown".</li>
 *   <li><b>Duplicates:</b> rows describing the same real-world hub under different
 *       IDs are merged (see {@link #mergeRecords}).</li>
 * </ul>
 *
 * <h2>REST API</h2>
 * <ul>
 *   <li>{@code GET /health} — liveness probe, returns {@code OK}.</li>
 *   <li>{@code GET /hubs} — all cleaned, de-duplicated hubs as a JSON array.</li>
 *   <li>{@code GET /hubs/{hubId}} — one hub by ID (case-insensitive), 404 if unknown.</li>
 * </ul>
 *
 * <p>Exposed record shape:</p>
 * <pre>{@code
 * {
 *   "hub_id": "H-500",
 *   "province": "Gauteng",
 *   "sorting_center": "Johannesburg Central",
 *   "active": true,
 *   "duplicate_ids": ["H-500", "H-504", "H-510", "H-515"]
 * }
 * }</pre>
 *
 * <p>{@code duplicate_ids} keeps every raw ID that was folded into the record, so
 * the de-duplication decision stays auditable by downstream consumers.</p>
 */
public class IngestionServiceApp {

    /** Placeholder tokens that mean "no value" in the raw export. */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "N/A", "NAN", "NULL", "TBD", "UNKNOWN", "-", "?");

    /** Canonical province spellings, keyed by a normalized (lower-case, separator-free) form. */
    private static final Map<String, String> PROVINCES = Map.ofEntries(
            Map.entry("gauteng", "Gauteng"),
            Map.entry("westerncape", "Western Cape"),
            Map.entry("kwazulunatal", "KwaZulu-Natal"),
            Map.entry("easterncape", "Eastern Cape"),
            Map.entry("freestate", "Free State"),
            Map.entry("northwest", "North West"),
            Map.entry("limpopo", "Limpopo"),
            Map.entry("mpumalanga", "Mpumalanga"),
            Map.entry("northerncape", "Northern Cape"));

    /** Value used when a hub's province cannot be determined from any source row. */
    static final String UNKNOWN_PROVINCE = "Unknown";

    /**
     * Boots the service: cleans the CSV from the classpath, starts Javalin on port
     * 7050, and wires the REST endpoints.
     *
     * @param args ignored
     * @throws IOException           if the CSV resource is missing or unreadable
     * @throws CsvValidationException if a CSV row cannot be parsed by opencsv
     */
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> hubs = loadHubs();

        Javalin app = Javalin.create().start(7050);

        /** Liveness probe used by humans and by the other services' health checks. */
        app.get("/health", ctx -> ctx.result("OK"));

        /** The cleaned dataset — the single entry point for hub-service. */
        app.get("/hubs", ctx -> ctx.json(hubs));

        /** Single-hub lookup; IDs are matched case-insensitively. */
        app.get("/hubs/{hubId}", ctx -> {
            String requestedId = normalizeHubId(ctx.pathParam("hubId"));

            for (Map<String, Object> hub : hubs) {
                if (hub.get("hub_id").equals(requestedId)) {
                    ctx.json(hub);
                    return;
                }
            }

            ctx.status(404).json(Map.of("error", "Hub not found"));
        });

        System.out.println("Ingestion service running on port 7050");
        System.out.println("Loaded " + hubs.size() + " unique hubs");
    }

    /**
     * Reads {@code hubs-global.csv} from the classpath and returns the cleaned,
     * de-duplicated hub records.
     *
     * @return cleaned hub records in first-seen order
     * @throws IOException           if the resource is missing or unreadable
     * @throws CsvValidationException if a CSV row cannot be parsed
     */
    static List<Map<String, Object>> loadHubs()
            throws IOException, CsvValidationException {

        InputStream stream = IngestionServiceApp.class
                .getResourceAsStream("/hubs-global.csv");

        if (stream == null) {
            throw new IOException(
                    "Could not find hubs-global.csv in src/main/resources");
        }

        List<String[]> rows = new ArrayList<>();

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();

            if (header == null) {
                throw new IOException("CSV file is empty");
            }

            Map<String, Integer> columns = new LinkedHashMap<>();

            for (int i = 0; i < header.length; i++) {
                columns.put(cleanField(header[i]).toLowerCase(Locale.ROOT), i);
            }

            String[] row;

            while ((row = reader.readNext()) != null) {
                rows.add(new String[]{
                        value(row, columns, "hub_id"),
                        value(row, columns, "province"),
                        value(row, columns, "sorting_center"),
                        value(row, columns, "active")
                });
            }
        }

        return buildHubRecords(rows);
    }

    /**
     * Turns raw CSV rows (already column-mapped, but not yet cleaned) into cleaned,
     * de-duplicated hub records. Split out from {@link #loadHubs()} so it can be
     * unit-tested without the classpath resource.
     *
     * <p>Pass 1 cleans every field; pass 1b infers missing provinces from other rows
     * that share the same sorting center; pass 2 merges duplicates keyed on
     * {@code province|sorting_center}.</p>
     *
     * @param rows rows shaped as {@code {hub_id, province, sorting_center, active}}
     * @return cleaned, de-duplicated records in first-seen order
     */
    static List<Map<String, Object>> buildHubRecords(List<String[]> rows) {

        // Pass 1: clean each field independently.
        List<Map<String, Object>> candidates = new ArrayList<>();

        for (String[] row : rows) {
            String id = normalizeHubId(row.length > 0 ? row[0] : "");
            String province = normalizeProvince(
                    row.length > 1 ? row[1] : "");
            String center = titleCase(
                    row.length > 2 ? row[2] : "");
            Boolean active = parseBoolean(
                    row.length > 3 ? row[3] : "");

            if (id.isEmpty() || center.isEmpty()) {
                System.out.println("Skipping row with unusable id/center: "
                        + String.join(" | ", row));
                continue;
            }

            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("hub_id", id);
            candidate.put("province", province);
            candidate.put("sorting_center", center);
            candidate.put("active", active);
            candidates.add(candidate);
        }

        // Pass 1b: recover rows whose province was blank/placeholder by looking at
        // other rows that name the same sorting center (e.g. "Pretoria North" is
        // always in Gauteng). Anything still unknown becomes UNKNOWN_PROVINCE.
        Map<String, String> provinceByCenter = new LinkedHashMap<>();

        for (Map<String, Object> candidate : candidates) {
            String center = (String) candidate.get("sorting_center");
            String province = (String) candidate.get("province");

            if (!province.isEmpty()) {
                provinceByCenter.putIfAbsent(
                        center.toLowerCase(Locale.ROOT), province);
            }
        }

        for (Map<String, Object> candidate : candidates) {
            if (((String) candidate.get("province")).isEmpty()) {
                String center = (String) candidate.get("sorting_center");
                candidate.put("province", provinceByCenter.getOrDefault(
                        center.toLowerCase(Locale.ROOT), UNKNOWN_PROVINCE));
            }
        }

        // Pass 2: de-duplicate on province + sorting center.
        Map<String, Map<String, Object>> uniqueHubs = new LinkedHashMap<>();

        for (Map<String, Object> candidate : candidates) {
            mergeRecords(uniqueHubs, candidate);
        }

        return new ArrayList<>(uniqueHubs.values());
    }

    /**
     * Merges one cleaned candidate into the de-duplication map, keyed on
     * {@code province|sorting_center} (lower-cased).
     *
     * <p>Dedup strategy — first-seen wins for identity, fail-safe wins for status:</p>
     * <ul>
     *   <li>The first row in file order keeps its hub ID; later rows for the same
     *       place are recorded only in {@code duplicate_ids}. File order is a stable,
     *       explainable tie-breaker, and the earlier IDs tend to be the "canonical"
     *       ones in the export.</li>
     *   <li>{@code active} adopts the first <em>known</em> boolean; conflicting
     *       known values resolve to {@code true} — for a logistics network it is
     *       safer to route a package to a hub that turns out to be closed than to
     *       silently drop it from the network because one legacy source said
     *       {@code false}.</li>
     * </ul>
     *
     * @param uniqueHubs accumulator of de-duplicated records, mutated in place
     * @param candidate  one cleaned record to fold in
     */
    static void mergeRecords(Map<String, Map<String, Object>> uniqueHubs,
                             Map<String, Object> candidate) {

        String key = candidate.get("province").toString()
                .toLowerCase(Locale.ROOT)
                + "|" + candidate.get("sorting_center").toString()
                .toLowerCase(Locale.ROOT);

        Map<String, Object> existing = uniqueHubs.get(key);

        if (existing == null) {
            candidate.put("duplicate_ids",
                    new ArrayList<>(List.of((String) candidate.get("hub_id"))));
            uniqueHubs.put(key, candidate);
            return;
        }

        // Record the merged-away ID so the decision stays auditable.
        @SuppressWarnings("unchecked")
        List<String> duplicateIds =
                (List<String>) existing.get("duplicate_ids");
        duplicateIds.add((String) candidate.get("hub_id"));

        Boolean knownActive = (Boolean) candidate.get("active");

        if (knownActive != null) {
            Boolean currentActive = (Boolean) existing.get("active");

            if (currentActive == null) {
                existing.put("active", knownActive);
            } else if (currentActive && !knownActive) {
                // Keep the record active while any source claims it is active.
                existing.put("active", true);
            }
        }
    }

    /**
     * Extracts a column's value from a raw CSV row by header name, defaulting to
     * an empty string when the column is absent or the row is short.
     *
     * @param row     raw CSV cells
     * @param columns header name → column index
     * @param name    column to fetch
     * @return the raw (still untrimmed) cell value, or {@code ""}
     */
    private static String value(String[] row,
                                Map<String, Integer> columns,
                                String name) {

        Integer index = columns.get(name.toLowerCase(Locale.ROOT));

        if (index == null || index >= row.length) {
            return "";
        }

        return row[index];
    }

    /**
     * Trims padding and collapses internal whitespace runs to single spaces, and
     * maps placeholder tokens ({@code N/A}, {@code TBD}, {@code unknown}, …) to
     * an empty string so they are treated as missing.
     *
     * @param value raw cell text, may be {@code null}
     * @return the cleaned value; empty string when the input was blank/placeholder
     */
    static String cleanField(String value) {
        if (value == null) {
            return "";
        }

        String result = value.trim().replaceAll("\\s+", " ");

        if (PLACEHOLDERS.contains(result.toUpperCase(Locale.ROOT))) {
            return "";
        }

        return result;
    }

    /**
     * Normalizes a hub ID: whitespace collapsed, upper-cased, so {@code " h-501 "}
     * becomes {@code "H-501"}.
     *
     * @param value raw hub ID
     * @return the normalized ID, or {@code ""} if unusable
     */
    static String normalizeHubId(String value) {
        return cleanField(value).toUpperCase(Locale.ROOT);
    }

    /**
     * Normalizes a province to its official South African spelling by matching a
     * lower-case, separator-stripped key ({@code "kwazulu-natal"} / {@code "KwaZulu Natal"}
     * → {@code "KwaZuluNatal"} → {@code "KwaZulu-Natal"}); unrecognised input falls
     * back to {@link #titleCase(String)}.
     *
     * @param province raw province text
     * @return canonical province name, or {@code ""} when missing
     */
    static String normalizeProvince(String province) {
        String cleaned = cleanField(province);

        if (cleaned.isEmpty()) {
            return "";
        }

        String key = cleaned.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "");

        return PROVINCES.getOrDefault(key, titleCase(cleaned));
    }

    /**
     * Title-cases a name: lower-cases everything, then capitalises each word, so
     * {@code "johannesburg central"} becomes {@code "Johannesburg Central"}.
     *
     * @param value raw name text
     * @return title-cased text, or {@code ""} when missing
     */
    static String titleCase(String value) {
        String cleaned = cleanField(value);

        if (cleaned.isEmpty()) {
            return "";
        }

        String[] words = cleaned.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(" ");
            }

            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            result.append(word.substring(1));
        }

        return result.toString();
    }

    /**
     * Normalizes the many boolean encodings in the export to a single form:
     * {@code Y/yes/1/true} (any case) → {@code true}, {@code N/no/0/false} →
     * {@code false}, anything else (e.g. {@code unknown}) → {@code null} meaning
     * "status unknown" rather than a guess.
     *
     * @param value raw flag text
     * @return {@code true}, {@code false}, or {@code null} when undecidable
     */
    static Boolean parseBoolean(String value) {
        return switch (cleanField(value).toLowerCase(Locale.ROOT)) {
            case "y", "yes", "1", "true" -> true;
            case "n", "no", "0", "false" -> false;
            default -> null;
        };
    }
}
