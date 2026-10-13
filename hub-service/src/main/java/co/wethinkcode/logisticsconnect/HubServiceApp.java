
package co.wethinkcode.logisticsconnect;
import io.javalin.Javalin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class HubServiceApp {

    private static final String INGESTION_URL =
            "http://localhost:7050";

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newHttpClient();

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    public static void main(String[] args) {

        Javalin app = Javalin.create().start(7051);

        app.get("/health", ctx -> ctx.result("OK"));

        // Return all hubs from the ingestion service.
        app.get("/hubs", ctx -> {
            try {
                ctx.json(getHubs());
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error",
                                "Could not retrieve hubs from ingestion service"));
            }
        });

        // Return all unique provinces.
        app.get("/provinces", ctx -> {
            try {
                List<Map<String, Object>> hubs = getHubs();

                TreeSet<String> provinces = new TreeSet<>();

                for (Map<String, Object> hub : hubs) {
                    Object province = hub.get("province");

                    if (province != null
                            && !province.toString().isBlank()) {
                        provinces.add(province.toString());
                    }
                }

                ctx.json(provinces);
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error",
                                "Could not retrieve provinces"));
            }
        });

        // Return sorting centers, optionally filtered by province.
        app.get("/sorting-centers", ctx -> {
            try {
                List<Map<String, Object>> hubs = getHubs();

                String requestedProvince =
                        ctx.queryParam("province");

                TreeSet<String> centers = new TreeSet<>();

                for (Map<String, Object> hub : hubs) {
                    String province =
                            String.valueOf(hub.get("province"));

                    String center =
                            String.valueOf(hub.get("sorting_center"));

                    if (requestedProvince == null
                            || province.equalsIgnoreCase(
                            requestedProvince.trim())) {
                        centers.add(center);
                    }
                }

                ctx.json(centers);
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error",
                                "Could not retrieve sorting centers"));
            }
        });

        // Return one hub by its ID.
        app.get("/hubs/{hubId}", ctx -> {
            try {
                String requestedId =
                        ctx.pathParam("hubId").trim();

                for (Map<String, Object> hub : getHubs()) {
                    Object id = hub.get("hub_id");

                    if (id != null
                            && id.toString().equalsIgnoreCase(
                            requestedId)) {
                        ctx.json(hub);
                        return;
                    }
                }

                ctx.status(404).json(
                        Map.of("error", "Hub not found"));
            } catch (Exception e) {
                ctx.status(502).json(
                        Map.of("error",
                                "Could not retrieve hub information"));
            }
        });

        System.out.println(
                "Hub service running on port 7051");
    }

    private static List<Map<String, Object>> getHubs()
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INGESTION_URL + "/hubs"))
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Ingestion service returned status "
                            + response.statusCode());
        }

        return OBJECT_MAPPER.readValue(
                response.body(),
                new TypeReference<
                        List<Map<String, Object>>>() {});
    }
}
