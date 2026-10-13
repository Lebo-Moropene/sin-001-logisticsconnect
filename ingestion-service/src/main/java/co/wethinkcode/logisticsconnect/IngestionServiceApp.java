
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

public class IngestionServiceApp {

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> hubs = loadHubs();

        Javalin app = Javalin.create().start(7050);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs", ctx -> ctx.json(hubs));

        app.get("/hubs/{hubId}", ctx -> {
            String requestedId = clean(ctx.pathParam("hubId"))
                    .toUpperCase(Locale.ROOT);

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

    private static List<Map<String, Object>> loadHubs()
            throws IOException, CsvValidationException {

        InputStream stream = IngestionServiceApp.class
                .getResourceAsStream("/hubs-global.csv");

        if (stream == null) {
            throw new IOException(
                    "Could not find hubs-global.csv in src/main/resources");
        }

        Map<String, Integer> columns = new LinkedHashMap<>();

        Map<String, Map<String, Object>> uniqueHubs =
                new LinkedHashMap<>();

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();

            if (header == null) {
                throw new IOException("CSV file is empty");
            }

            for (int i = 0; i < header.length; i++) {
                columns.put(
                        clean(header[i]).toLowerCase(Locale.ROOT), i);
            }

            String[] row;

            while ((row = reader.readNext()) != null) {
                String id = value(row, columns, "hub_id")
                        .toUpperCase(Locale.ROOT);

                String province = normalizeProvince(
                        value(row, columns, "province"));

                String center = titleCase(
                        value(row, columns, "sorting_center"));

                Boolean active = parseBoolean(
                        value(row, columns, "active"));

                if (id.isEmpty()
                        || province.isEmpty()
                        || center.isEmpty()) {
                    continue;
                }

                Map<String, Object> hub = new LinkedHashMap<>();
                hub.put("hub_id", id);
                hub.put("province", province);
                hub.put("sorting_center", center);
                hub.put("active", active);

                String key = province.toLowerCase(Locale.ROOT)
                        + "|" + center.toLowerCase(Locale.ROOT);

                uniqueHubs.putIfAbsent(key, hub);
            }
        }

        return new ArrayList<>(uniqueHubs.values());
    }

    private static String value(
            String[] row,
            Map<String, Integer> columns,
            String name) {

        Integer index = columns.get(name.toLowerCase(Locale.ROOT));

        if (index == null || index >= row.length) {
            return "";
        }

        return clean(row[index]);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }

        String result = value.trim().replaceAll("\\s+", " ");

        if (List.of("N/A", "TBD", "UNKNOWN", "-", "NAN")
                .contains(result.toUpperCase(Locale.ROOT))) {
            return "";
        }

        return result;
    }

    private static String normalizeProvince(String province) {
        String key = province.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "");

        return switch (key) {
            case "gauteng" -> "Gauteng";
            case "westerncape" -> "Western Cape";
            case "kwazulunatal" -> "KwaZulu-Natal";
            case "easterncape" -> "Eastern Cape";
            case "freestate" -> "Free State";
            case "northwest" -> "North West";
            case "limpopo" -> "Limpopo";
            case "mpumalanga" -> "Mpumalanga";
            case "northerncape" -> "Northern Cape";
            default -> titleCase(province);
        };
    }

    private static String titleCase(String value) {
        String cleaned = clean(value);

        if (cleaned.isEmpty()) {
            return "";
        }

        String[] words = cleaned.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(" ");
            }

            result.append(
                    word.substring(0, 1).toUpperCase(Locale.ROOT));
            result.append(word.substring(1));
        }

        return result.toString();
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "y", "yes", "1", "true" -> true;
            case "n", "no", "0", "false" -> false;
            default -> null;
        };
    }
}
