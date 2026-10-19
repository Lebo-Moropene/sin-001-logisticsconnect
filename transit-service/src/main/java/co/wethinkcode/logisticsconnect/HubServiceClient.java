package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Reads hub details from hub-service (GET :7051/hubs/{hubId}).
 */
public class HubServiceClient {

    private static final String BASE_URL = "http://localhost:7051";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the hub record, or null when hub-service does not know the id.
     */
    public Map<String, Object> findHub(String hubId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/hubs/"
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
                    "hub-service returned status "
                            + response.statusCode());
        }

        return objectMapper.readValue(
                response.body(),
                new TypeReference<Map<String, Object>>() {});
    }
}
