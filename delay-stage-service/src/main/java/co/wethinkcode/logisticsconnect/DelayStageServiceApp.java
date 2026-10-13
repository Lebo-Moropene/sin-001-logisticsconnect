
package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages transit delay stages for individual hubs.
 *
 * Each hub has a delay stage between 0 and 8.
 * Stages are stored in memory and are cleared when the service restarts.
 *
 * The service exposes REST endpoints for retrieving and updating stages.
 * ActiveMQ publishing will be added when the messaging stage is implemented.
 */
public class DelayStageServiceApp {

    private static final Map<String, Integer> DELAY_STAGES =
            new ConcurrentHashMap<>();

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    public static void main(String[] args) {

        Javalin app = Javalin.create().start(7052);

        app.get("/health", ctx -> ctx.result("OK"));

        // TODO (Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns).)
        // Add domain endpoints for delay-stage-service here.

        // Return the current delay stage for one hub.
        app.get("/delay-stage/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId")
                    .trim().toUpperCase();

            Integer stage = DELAY_STAGES.get(hubId);

            if (stage == null) {
                ctx.status(404).json(
                        Map.of("error", "Delay stage not found for hub"));
                return;
            }

            ctx.json(Map.of(
                    "hubId", hubId,
                    "stage", stage
            ));
        });

        // Create or update a hub's delay stage.
        app.post("/delay-stage/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId")
                    .trim().toUpperCase();

            if (hubId.isBlank()) {
                ctx.status(400).json(
                        Map.of("error", "Hub ID is required"));
                return;
            }

            JsonNode body;

            // Reject malformed JSON before processing the stage.
            try {
                body = OBJECT_MAPPER.readTree(ctx.body());
            } catch (Exception e) {
                ctx.status(400).json(
                        Map.of("error", "Request body must be valid JSON"));
                return;
            }

            // Require an integer stage that can be represented as an int.
            if (body == null
                    || !body.has("stage")
                    || !body.get("stage").isIntegralNumber()
                    || !body.get("stage").canConvertToInt()) {
                ctx.status(400).json(
                        Map.of("error", "Stage must be an integer from 0 to 8"));
                return;
            }

            int stage = body.get("stage").intValue();

            // Only stages between 0 and 8 are accepted.
            if (stage < 0 || stage > 8) {
                ctx.status(400).json(
                        Map.of("error", "Stage must be between 0 and 8"));
                return;
            }

            // Save the stage against the hub ID.
            DELAY_STAGES.put(hubId, stage);

            // Return the updated stage and the time of the update.
            ctx.json(Map.of(
                    "hubId", hubId,
                    "stage", stage,
                    "timestamp", Instant.now().toString()
            ));
        });

        System.out.println("Delay-stage service running on port 7052");
    }
}

// TODO: Publish stage changes to ActiveMQ topic MqConfig.TOPIC
// using MqConfig.BROKER_URL. See co.wethinkcode.logisticsconnect.mq.MqConfig.
