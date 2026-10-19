package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Reads the current delay stage from delay-stage-service
 * (GET :7052/delay-stage/{hubId}).
 */
public class DelayStageClient {

    private static final String BASE_URL = "http://localhost:7052";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the current stage, or null when no stage has been set yet.
     */
    public Integer findDelayStage(String hubId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/delay-stage/"
                        + hubId.trim()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            return null;
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "delay-stage-service returned status "
                            + response.statusCode());
        }

        JsonNode body = objectMapper.readTree(response.body());

        if (!body.hasNonNull("stage")
                || !body.get("stage").canConvertToInt()) {
            throw new IllegalStateException(
                    "delay-stage-service returned no numeric stage");
        }

        return body.get("stage").intValue();
    }
}
