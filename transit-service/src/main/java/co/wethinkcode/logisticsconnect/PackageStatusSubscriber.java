package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes to package-status-topic and keeps a DelayStageCache up to
 * date from the messages delay-stage-service publishes. Replaces the
 * direct REST call transit-service used to make on every ETA request.
 */
public class PackageStatusSubscriber {

    private final DelayStageCache cache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicLong received = new AtomicLong();

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;

    public PackageStatusSubscriber(DelayStageCache cache) {
        this.cache = cache;
    }

    /**
     * Connects to the broker and begins delivering messages. Throws if
     * the broker is unreachable, so startup fails loudly instead of
     * silently serving stale stages.
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
                "Subscribed to ActiveMQ topic: " + MqConfig.TOPIC);
    }

    /**
     * Reads one package-status message into the cache. A malformed
     * message is logged and dropped rather than killing the listener.
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

        cache.update(hubId, stage);
        received.incrementAndGet();

        System.out.println(
                "Received delay-stage event for " + hubId
                        + ": stage " + stage);
    }

    public long receivedCount() {
        return received.get();
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
