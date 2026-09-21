package com.sukhraj.infra;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container health probe that verifies the controller API, not only the JVM process. */
public final class HealthProbe {
    private HealthProbe() {}

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("PLATFORM_PORT", "8080");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
                System.exit(1);
            }
        } catch (Exception exception) {
            System.exit(1);
        }
    }
}
