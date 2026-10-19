package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

public class TransitServiceApp {

    private static final HubServiceClient HUB_CLIENT =
            new HubServiceClient();

    private static final DelayStageCache DELAY_STAGE_CACHE =
            new DelayStageCache();

    private static final EtaCalculator ETA_CALCULATOR =
            new EtaCalculator();

    public static void main(String[] args) throws Exception {
        PackageStatusSubscriber subscriber =
                new PackageStatusSubscriber(DELAY_STAGE_CACHE);

        // The broker is a hard dependency: without it this service
        // would never learn about delay stages.
        subscriber.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                subscriber.close();
            } catch (Exception e) {
                System.err.println(
                        "Error closing ActiveMQ resources: "
                                + e.getMessage());
            }
        }));

        Javalin app = Javalin.create().start(7053);

        app.get("/health", ctx -> ctx.result("OK"));

        // Estimated arrival window for one hub, built from
        // hub-service (location) and delay-stage-service (stage).
        app.get("/eta/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").trim();

            if (hubId.isBlank()) {
                ctx.status(400).json(
                        Map.of("error", "Hub ID is required"));
                return;
            }

            Map<String, Object> hub;

            try {
                hub = HUB_CLIENT.findHub(hubId);
            } catch (Exception e) {
                ctx.status(502).json(Map.of(
                        "error",
                        "Could not retrieve hub from hub-service"));
                return;
            }

            if (hub == null) {
                ctx.status(404).json(
                        Map.of("error", "Hub not found"));
                return;
            }

            // The stage comes from the package-status-topic subscription
            // rather than a synchronous call to delay-stage-service.
            Integer stage = DELAY_STAGE_CACHE.find(hubId);

            // A hub the topic has not mentioned yet is running normally.
            int delayStage = stage == null ? 0 : stage;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("hubId",
                    String.valueOf(hub.get("hub_id")));
            response.put("province", hub.get("province"));
            response.put("sortingCenter",
                    hub.get("sorting_center"));
            response.put("active", hub.get("active"));
            response.put("delayStage", delayStage);
            response.put("eta",
                    ETA_CALCULATOR.estimate(delayStage));

            ctx.json(response);
        });
    }
}
