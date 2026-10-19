package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

public class TransitServiceApp {

    private static final HubServiceClient HUB_CLIENT =
            new HubServiceClient();

    private static final DelayStageClient DELAY_STAGE_CLIENT =
            new DelayStageClient();

    private static final EtaCalculator ETA_CALCULATOR =
            new EtaCalculator();

    public static void main(String[] args) {
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

            Integer stage;

            try {
                stage = DELAY_STAGE_CLIENT.findDelayStage(hubId);
            } catch (Exception e) {
                ctx.status(502).json(Map.of(
                        "error",
                        "Could not retrieve delay stage from delay-stage-service"));
                return;
            }

            // A hub with no recorded stage is running normally.
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
