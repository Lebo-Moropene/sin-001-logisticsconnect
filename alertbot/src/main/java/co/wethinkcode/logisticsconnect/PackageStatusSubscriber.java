package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes to package-status-topic and turns delay stage updates
 * into simulated social posts whenever {@link AlertPolicy} says the
 * threshold has just been crossed.
 */
public class PackageStatusSubscriber {

    private final DelayStageCache cache;

    private final AlertPolicy policy;

    private final SocialAlertFeed feed;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicLong received = new AtomicLong();

    private final AtomicLong alerts = new AtomicLong();

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;

    public PackageStatusSubscriber(
            DelayStageCache cache,
            AlertPolicy policy,
            SocialAlertFeed feed) {
        this.cache = cache;
        this.policy = policy;
        this.feed = feed;
    }

    /**
     * Connects to the broker and begins delivering messages. Throws if
     * the broker is unreachable, so startup fails loudly.
     */
    public void start() throws JMSException {
        ActiveMQConnectionFactory factory =
                new ActiveMQConnectionFactory(MqConfig.BROKER_URL);

        connection = factory.createConnection();
        session = connection.createSession(
                false, Session.AUTO_ACKNOWLEDGE);

        Topic topic = session.createTopic(MqConfig.TOPIC);
        consumer = session.createConsumer(topic);
        consumer.setMessageListener(message -> onMessage(message));

        connection.start();

        System.out.println(
                "Subscribed to ActiveMQ topic: " + MqConfig.TOPIC
                        + " (alert threshold: stage "
                        + policy.threshold() + ")");
    }

    /**
     * Applies one package-status message: update the stage, then alert
     * if the threshold was freshly crossed. Malformed messages are
     * logged and dropped rather than killing the listener.
     */
    void handle(String json) throws Exception {
        JsonNode body = objectMapper.readTree(json);

        if (!body.hasNonNull("hubId")
                || !body.hasNonNull("stage")
                || !body.get("stage").canConvertToInt()) {
            System.err.println(
                    "Ignoring package-status message without hubId/stage: "
                            + json);
            return;
        }

        String hubId = body.get("hubId").asText();
        int stage = body.get("stage").intValue();

        Integer previousStage = cache.find(hubId);
        cache.update(hubId, stage);
        received.incrementAndGet();

        if (policy.shouldAlert(previousStage, stage)) {
            feed.post(hubId, stage);
            alerts.incrementAndGet();
            return;
        }

        System.out.println(
                "No alert for " + hubId + ": stage " + stage
                        + " (previous: " + previousStage + ")");
    }

    public long receivedCount() {
        return received.get();
    }

    public long alertCount() {
        return alerts.get();
    }

    public void close() throws JMSException {
        if (consumer != null) {
            consumer.close();
        }
        if (session != null) {
            session.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    private void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) {
            System.err.println(
                    "Ignoring non-text package-status message");
            return;
        }

        try {
            handle(text.getText());
        } catch (Exception e) {
            System.err.println(
                    "Ignoring unreadable package-status message: "
                            + e.getMessage());
        }
    }
}
