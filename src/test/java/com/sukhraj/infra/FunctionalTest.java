package com.sukhraj.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sukhraj.infra.api.ApiServer;
import com.sukhraj.infra.client.AgentClient;
import com.sukhraj.infra.model.NodeDefinition;
import com.sukhraj.infra.monitoring.MonitoringService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FunctionalTest {
    private static int assertions;

    private FunctionalTest() {}

    public static void main(String[] args) throws Exception {
        AtomicBoolean failMetrics = new AtomicBoolean(false);
        HttpServer agent = fakeAgent(failMetrics);
        agent.start();
        int agentPort = agent.getAddress().getPort();

        NodeDefinition node = new NodeDefinition(
                "east",
                "East test node",
                URI.create("http://127.0.0.1:" + agentPort),
                "node-secret");
        MonitoringService monitoring = new MonitoringService(
                List.of(node),
                new AgentClient(Duration.ofSeconds(2)),
                Duration.ofHours(1),
                3);
        ApiServer api = new ApiServer("127.0.0.1", 0, monitoring, "operator-secret");
        api.start();

        try {
            String baseUrl = "http://127.0.0.1:" + api.port();
            response(baseUrl, "GET", "/api/v1/health", null, null, 200, "\"status\":\"UP\"");
            response(baseUrl, "GET", "/api/v1/nodes", null, null, 200, "\"count\":1");

            response(baseUrl, "POST", "/api/v1/nodes/east/collect", null, null, 200, "\"cpu\"");
            response(baseUrl, "GET", "/api/v1/nodes/east/metrics/latest", null, null, 200, "\"usagePercent\":12.5");
            response(baseUrl, "GET", "/api/v1/nodes/east/availability", null, null, 200, "\"availabilityPercent\":100.00");

            failMetrics.set(true);
            response(baseUrl, "POST", "/api/v1/nodes/east/collect", null, null, 502, "Agent returned HTTP 500");
            response(baseUrl, "GET", "/api/v1/nodes/east/availability", null, null, 200, "\"availabilityPercent\":50.00");
            response(baseUrl, "GET", "/api/v1/nodes/east/metrics/latest", null, null, 200, "\"usagePercent\":12.5");

            response(baseUrl, "GET", "/api/v1/nodes/east/logs?unit=nginx&lines=20", null, null, 401, "invalid operator token");
            response(baseUrl, "GET", "/api/v1/nodes/east/logs?unit=nginx&lines=20", "operator-secret", null, 200, "service ready");
            response(baseUrl, "POST", "/api/v1/nodes/east/actions", "wrong", "{\"action\":\"disk_usage\"}", 401, "invalid operator token");
            response(baseUrl, "POST", "/api/v1/nodes/east/actions", "operator-secret", null, 400, "request body must contain");
            response(baseUrl, "POST", "/api/v1/nodes/east/actions", "operator-secret", "{\"action\":\"disk_usage\"}", 200, "\"executed\":true");

            response(baseUrl, "GET", "/api/v1/reports/operations", null, null, 200, "infrastructure-operations");
            response(baseUrl, "GET", "/api/v1/reports/operations", null, null, 200, "East test node");
            response(baseUrl, "GET", "/api/v1/nodes/missing/availability", null, null, 404, "unknown node");
            response(baseUrl, "GET", "/api/v1/nodes/east/collect", null, null, 405, "method not allowed");
            response(baseUrl, "GET", "/not-a-route", null, null, 404, "route not found");
        } finally {
            api.close();
            monitoring.close();
            agent.stop(0);
        }
        System.out.println("PASS: " + assertions + " Java functional assertions");
    }

    private static HttpServer fakeAgent(AtomicBoolean failMetrics) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", exchange -> {
            if (!authorized(exchange)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else if (failMetrics.get()) {
                send(exchange, 500, "{\"error\":\"collection failed\"}");
            } else {
                send(exchange, 200,
                        "{\"nodeId\":\"east\",\"cpu\":{\"usagePercent\":12.5},\"memory\":{\"usedPercent\":42.0}}");
            }
        });
        server.createContext("/logs", exchange -> {
            if (!authorized(exchange)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else {
                send(exchange, 200, "{\"unit\":\"nginx\",\"output\":\"service ready\"}");
            }
        });
        server.createContext("/actions", exchange -> {
            if (!authorized(exchange)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method\"}");
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                send(exchange, 200, "{\"executed\":true,\"request\":" + quote(body) + "}");
            }
        });
        return server;
    }

    private static boolean authorized(HttpExchange exchange) {
        return "Bearer node-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void response(
            String baseUrl,
            String method,
            String path,
            String token,
            String body,
            int expectedStatus,
            String expectedText) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(3));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == expectedStatus,
                method + " " + path + " expected HTTP " + expectedStatus + " but got " + response.statusCode());
        check(response.body().contains(expectedText),
                method + " " + path + " response should contain " + expectedText + " but was " + response.body());
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
