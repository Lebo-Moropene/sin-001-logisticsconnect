package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * LogisticsConnect hub service (port 7051) — the place-name source of truth for
 * provinces and sorting centers.
 *
 * <p>Does not parse the CSV itself: it loads the cleaned dataset published by
 * ingestion-service ({@code GET :7050/hubs}) on each request and derives its
 * endpoints from it, so there is exactly one implementation of the messy-CSV
 * cleaning rules.</p>
 *
 * <h2>REST API</h2>
 * <ul>
 *   <li>{@code GET /health} — liveness probe, returns {@code OK}.</li>
 *   <li>{@code GET /hubs} — all cleaned hubs (proxied from ingestion-service).</li>
 *   <li>{@code GET /hubs/{hubId}} — one hub by ID, case-insensitive; 404 if unknown.</li>
 *   <li>{@code GET /provinces} — distinct province names, sorted.</li>
 *   <li>{@code GET /sorting-centers?province=Gauteng} — distinct sorting-center names,
 *       optionally filtered by province (case-insensitive match).</li>
 * </ul>
 *
 * <p>Upstream failures (ingestion-service down or erroring) surface as
 * {@code 502 Bad Gateway} with a JSON error body.</p>
 */
public class HubServiceApp {

    /** Base URL of the ingestion service, which owns the cleaned CSV data. */
    private static final String INGESTION_URL = "http://localhost:7050";

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Boots the service: starts Javalin on port 7051 and wires the endpoints.
     *
     * @param args ignored
     */
    public static void main(String[] args) {

        Javalin app = Javalin.create().start(7051);

        /** Liveness probe. */
        app.get("/health", ctx -> ctx.result("OK"));

        /** All cleaned hubs, straight from ingestion-service. */
        app.get("/hubs", ctx -> {
            try {
                ctx.json(getHubs());
            } catch (Exception e) {
                ctx.status(502).json(Map.of("error",
                        "Could not retrieve hubs from ingestion service"));
            }
        });

        /** Distinct provinces, sorted alphabetically. */
        app.get("/provinces", ctx -> {
            try {
                List<Map<String, Object>> hubs = getHubs();

                TreeSet<String> provinces = new TreeSet<>();

                for (Map<String, Object> hub : hubs) {
                    Object province = hub.get("province");

                    if (province != null && !province.toString().isBlank()) {
                        provinces.add(province.toString());
                    }
                }

                ctx.json(provinces);
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error", "Could not retrieve provinces"));
            }
        });

        /** Distinct sorting centers, optionally filtered by {@code ?province=}. */
        app.get("/sorting-centers", ctx -> {
            try {
                List<Map<String, Object>> hubs = getHubs();

                String requestedProvince = ctx.queryParam("province");

                TreeSet<String> centers = new TreeSet<>();

                for (Map<String, Object> hub : hubs) {
                    String province = String.valueOf(hub.get("province"));
                    String center = String.valueOf(hub.get("sorting_center"));

                    if (requestedProvince == null
                            || province.equalsIgnoreCase(
                            requestedProvince.trim())) {
                        centers.add(center);
                    }
                }

                ctx.json(centers);
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error", "Could not retrieve sorting centers"));
            }
        });

        /** Single hub lookup — transit-service uses this to locate a hub. */
        app.get("/hubs/{hubId}", ctx -> {
            try {
                String requestedId = ctx.pathParam("hubId").trim();

                for (Map<String, Object> hub : getHubs()) {
                    Object id = hub.get("hub_id");

                    if (id != null
                            && id.toString().equalsIgnoreCase(requestedId)) {
                        ctx.json(hub);
                        return;
                    }
                }

                ctx.status(404).json(Map.of("error", "Hub not found"));
            } catch (Exception e) {
                ctx.status(502).json(Map.of("error",
                        "Could not retrieve hub information"));
            }
        });

        System.out.println("Hub service running on port 7051");
    }

    /**
     * Fetches the full cleaned hub list from ingestion-service over HTTP.
     *
     * @return the cleaned hubs as a list of field-name → value maps
     * @throws Exception on transport failure, or if ingestion-service returns a
     *                   non-200 status
     */
    private static List<Map<String, Object>> getHubs() throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INGESTION_URL + "/hubs"))
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Ingestion service returned status " + response.statusCode());
        }

        return OBJECT_MAPPER.readValue(response.body(),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }
}
