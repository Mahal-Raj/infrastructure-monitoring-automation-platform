package com.sukhraj.infra.client;

import com.sukhraj.infra.model.NodeDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class AgentClient {
    private static final int MAX_RESPONSE_CHARS = 256 * 1024;
    private final HttpClient client;
    private final Duration requestTimeout;

    public AgentClient(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public AgentResponse get(NodeDefinition node, String pathAndQuery) {
        return request(node, "GET", pathAndQuery, null);
    }

    public AgentResponse post(NodeDefinition node, String pathAndQuery, String body) {
        return request(node, "POST", pathAndQuery, body);
    }

    private AgentResponse request(NodeDefinition node, String method, String pathAndQuery, String body) {
        long started = System.nanoTime();
        try {
            URI uri = URI.create(node.baseUri() + pathAndQuery);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", "infrastructure-controller/1.0");
            if (!node.token().isBlank()) {
                builder.header("Authorization", "Bearer " + node.token());
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = elapsedMs(started);
            if (response.body().length() > MAX_RESPONSE_CHARS) {
                return AgentResponse.failure(response.statusCode(), latency, "Agent response exceeded 256 KiB");
            }
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return new AgentResponse(success, response.statusCode(), response.body(), latency,
                    success ? null : "Agent returned HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return AgentResponse.failure(0, elapsedMs(started), "Agent request was interrupted");
        } catch (Exception exception) {
            return AgentResponse.failure(0, elapsedMs(started),
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        }
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "request failed" : exception.getMessage();
    }

    public record AgentResponse(boolean success, int statusCode, String body, long latencyMs, String error) {
        public static AgentResponse failure(int statusCode, long latencyMs, String error) {
            return new AgentResponse(false, statusCode, "", latencyMs, error);
        }
    }
}
