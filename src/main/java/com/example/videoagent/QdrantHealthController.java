package com.example.videoagent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class QdrantHealthController {

    private final RestClient client;

    public QdrantHealthController(
            @Value("${qdrant.url}") String url,
            @Value("${qdrant.api-key}") String apiKey
    ) {
        this.client = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("api-key", apiKey)
                .build();
    }

    @GetMapping("/health/qdrant")
    public Map<String, Object> health() {
        JsonNode response = client.get()
                .uri("/collections")
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !"ok".equals(response.path("status").asText())) {
            throw new IllegalStateException("Qdrant 返回异常");
        }

        return Map.of(
                "component", "qdrant",
                "status", "UP",
                "collectionCount",
                response.path("result").path("collections").size()
        );
    }
}