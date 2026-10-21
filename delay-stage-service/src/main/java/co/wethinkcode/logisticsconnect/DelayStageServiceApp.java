package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LogisticsConnect delay-stage service (port 7052) — tracks the Transit Delay
 * Stage (0–8, e.g. weather shutdowns) for each hub.
 *
 * <p>State is held in memory (cleared on restart). Successful stage changes are
 * published to the ActiveMQ topic {@code package-status-topic} (see
 * {@link MqConfig}) so downstream services — transit-service, alertbot — can react
 * without this service knowing or caring who is listening.</p>
 *
 * <h2>REST API</h2>
 * <ul>
 *   <li>{@code GET /health} — liveness probe, returns {@code OK}.</li>
 *   <li>{@code GET /delay-stages} — every hub's current stage.</li>
 *   <li>{@code GET /delay-stage/{hubId}} — one hub's stage; 404 if never set.</li>
 *   <li>{@code POST /delay-stage/{hubId}} with body {@code {"stage": 3}} —
 *       create/replace the stage (0–8) and publish a message to the topic.</li>
 * </ul>
 *
 * <h2>MQ contract</h2>
 * <p>Every accepted POST publishes a JSON body of the form
 * {@code {"hubId": "H-501", "stage": 5, "timestamp": "…"}} to
 * {@code package-status-topic}. If the broker publish fails the update is rejected
 * with {@code 503} — the stage <em>was</em> saved, but callers are told the event
 * was not broadcast.</p>
 */
public class DelayStageServiceApp {

    /** In-memory hub ID → delay stage store. */
    private static final Map<String, Integer> DELAY_STAGES =
            new ConcurrentHashMap<>();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Guards JMS session/producer use — JMS objects are not thread-safe. */
    private static final Object MQ_LOCK = new Object();

    private static Connection connection;
    private static Session session;
    private static MessageProducer producer;

    /**
     * Boots the service: connects to ActiveMQ (failing fast if the broker is
     * unreachable), starts Javalin on port 7052, wires the endpoints, and registers
     * a shutdown hook to close JMS resources.
     *
     * @param args ignored
     * @throws JMSException if the broker connection cannot be established
     */
    public static void main(String[] args) throws Exception {

        // Connect to ActiveMQ before accepting requests.
        connectToBroker();

        // Start the REST service.
        Javalin app = Javalin.create().start(7052);

        /** Liveness probe. */
        app.get("/health", ctx -> ctx.result("OK"));

        /** Snapshot of every known hub's delay stage. */
        app.get("/delay-stages", ctx -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();

            DELAY_STAGES.forEach((hubId, stage) ->
                    snapshot.put(hubId, Map.of("hubId", hubId, "stage", stage)));

            ctx.json(snapshot);
        });

        /** Return the current delay stage for one hub. */
        app.get("/delay-stage/{hubId}", ctx -> {
            String hubId = normalizeHubId(ctx.pathParam("hubId"));

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

        /** Create or update a hub's delay stage, then publish the change to MQ. */
        app.post("/delay-stage/{hubId}", ctx -> {
            String hubId = normalizeHubId(ctx.pathParam("hubId"));

            if (hubId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Hub ID is required"));
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

            String timestamp = Instant.now().toString();

            // Publish the successful update to ActiveMQ.
            try {
                publishStageChange(hubId, stage, timestamp);
            } catch (Exception e) {
                System.err.println(
                        "Failed to publish delay-stage update: " + e.getMessage());

                ctx.status(503).json(Map.of(
                        "error", "Stage saved, but the ActiveMQ message could not be published"
                ));
                return;
            }

            // Return the updated stage and the time of the update.
            ctx.json(Map.of(
                    "hubId", hubId,
                    "stage", stage,
                    "timestamp", timestamp
            ));
        });

        // Close messaging resources when the JVM shuts down.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (producer != null) {
                    producer.close();
                }
                if (session != null) {
                    session.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (JMSException e) {
                System.err.println(
                        "Error closing ActiveMQ resources: " + e.getMessage());
            }
        }));

        System.out.println("Delay-stage service running on port 7052");
    }

    /**
     * Normalizes a hub ID (trim + upper-case) so {@code " h-501 "} and {@code "H-501"}
     * address the same entry, matching ingestion-service's ID conventions.
     *
     * @param hubId raw hub ID from the path
     * @return the normalized hub ID
     */
    private static String normalizeHubId(String hubId) {
        return hubId == null ? "" : hubId.trim().toUpperCase();
    }

    /**
     * Connects to ActiveMQ and prepares the topic producer.
     *
     * @throws JMSException if the connection or session cannot be created
     */
    private static void connectToBroker() throws JMSException {
        ActiveMQConnectionFactory factory =
                new ActiveMQConnectionFactory(MqConfig.BROKER_URL);

        connection = factory.createConnection();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        Topic topic = session.createTopic(MqConfig.TOPIC);
        producer = session.createProducer(topic);
        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        connection.start();

        System.out.println("Connected to ActiveMQ topic: " + MqConfig.TOPIC);
    }

    /**
     * Publishes a JSON message to {@code package-status-topic} whenever a hub's
     * delay stage changes.
     *
     * @param hubId     the hub whose stage changed
     * @param stage     the new stage (0–8)
     * @param timestamp ISO-8601 instant of the change
     * @throws Exception on JMS serialization/transport failure
     */
    private static void publishStageChange(String hubId, int stage,
                                           String timestamp) throws Exception {

        Map<String, Object> event = Map.of(
                "hubId", hubId,
                "stage", stage,
                "timestamp", timestamp
        );

        String json = OBJECT_MAPPER.writeValueAsString(event);

        // JMS sessions are not thread-safe, so protect message creation and sending.
        synchronized (MQ_LOCK) {
            TextMessage message = session.createTextMessage(json);
            producer.send(message);
        }

        System.out.println("Published delay-stage event: " + json);
    }
}
