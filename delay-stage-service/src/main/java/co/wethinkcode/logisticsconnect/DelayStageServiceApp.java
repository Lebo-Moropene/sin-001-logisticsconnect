
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages transit delay stages for individual hubs.
 *
 * Each hub has a delay stage between 0 and 8.
 * Stages are stored in memory and are cleared when the service restarts.
 *
 * The service exposes REST endpoints for retrieving and updating stages.
 * Successful updates are published to an ActiveMQ topic.
 */
public class DelayStageServiceApp {

    private static final Map<String, Integer> DELAY_STAGES =
            new ConcurrentHashMap<>();

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final Object MQ_LOCK = new Object();

    private static Connection connection;
    private static Session session;
    private static MessageProducer producer;

    public static void main(String[] args) throws Exception {

        // Connect to ActiveMQ before accepting requests.
        connectToBroker();

        // Start the REST service.
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

            String timestamp = Instant.now().toString();

            // Publish the successful update to ActiveMQ.
            try {
                publishStageChange(hubId, stage, timestamp);
            } catch (Exception e) {
                System.err.println(
                        "Failed to publish delay-stage update: "
                                + e.getMessage());

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
     * Connects to ActiveMQ and prepares the topic producer.
     */
    private static void connectToBroker() throws JMSException {
        ActiveMQConnectionFactory factory =
                new ActiveMQConnectionFactory(MqConfig.BROKER_URL);

        connection = factory.createConnection();
        session = connection.createSession(
                false, Session.AUTO_ACKNOWLEDGE);

        Topic topic = session.createTopic(MqConfig.TOPIC);
        producer = session.createProducer(topic);
        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        connection.start();

        System.out.println(
                "Connected to ActiveMQ topic: " + MqConfig.TOPIC);
    }

    /**
     * Publishes a JSON message whenever a hub's delay stage changes.
     */
    private static void publishStageChange(
            String hubId, int stage, String timestamp) throws Exception {

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

