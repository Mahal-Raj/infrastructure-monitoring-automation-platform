package com.sukhraj.infra.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sukhraj.infra.client.AgentClient.AgentResponse;
import com.sukhraj.infra.json.Json;
import com.sukhraj.infra.monitoring.MonitoringService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class ApiServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final Pattern SAFE_UNIT = Pattern.compile("[A-Za-z0-9_.@-]{1,120}");
    private final HttpServer server;
    private final MonitoringService monitoring;
    private final String operatorToken;

    public ApiServer(String bindAddress, int port, MonitoringService monitoring, String operatorToken) throws IOException {
        this.monitoring = monitoring;
        this.operatorToken = operatorToken == null ? "" : operatorToken;
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newFixedThreadPool(12, runnable -> {
            Thread thread = new Thread(runnable, "api-handler");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            status = route(exchange);
        } catch (IllegalArgumentException exception) {
            status = sendError(exchange, 404, exception.getMessage());
        } catch (Exception exception) {
            status = sendError(exchange, 500, "internal server error");
            System.err.println("API error: " + exception);
        } finally {
            long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            System.out.printf("%s %s %d %dms%n", exchange.getRequestMethod(), exchange.getRequestURI(), status, elapsed);
            exchange.close();
        }
    }

    private int route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/api/v1/health".equals(path)) {
            return sendJson(exchange, 200, Json.object(
                    "status", Json.string("UP"),
                    "version", Json.string("1.0.0"),
                    "time", Json.string(Instant.now().toString()),
                    "nodesConfigured", Json.number(monitoring.nodeCount())));
        }
        if ("GET".equals(method) && "/api/v1/nodes".equals(path)) {
            return sendJson(exchange, 200, monitoring.nodesJson());
        }
        if ("GET".equals(method) && "/api/v1/reports/operations".equals(path)) {
            return sendJson(exchange, 200, monitoring.reportJson());
        }

        String[] segments = path.startsWith("/") ? path.substring(1).split("/") : path.split("/");
        if (segments.length < 5
                || !"api".equals(segments[0])
                || !"v1".equals(segments[1])
                || !"nodes".equals(segments[2])) {
            return sendError(exchange, 404, "route not found");
        }
        String nodeId = URLDecoder.decode(segments[3], StandardCharsets.UTF_8);
        if (!monitoring.contains(nodeId)) {
            return sendError(exchange, 404, "unknown node: " + nodeId);
        }

        if (segments.length == 5 && "collect".equals(segments[4])) {
            if (!"POST".equals(method)) {
                return sendError(exchange, 405, "method not allowed");
            }
            AgentResponse response = monitoring.collect(nodeId);
            if (!response.success()) {
                return sendAgentFailure(exchange, response);
            }
            return sendJson(exchange, 200, Json.object(
                    "nodeId", Json.string(nodeId),
                    "collected", Json.bool(true),
                    "latencyMs", Json.number(response.latencyMs()),
                    "metrics", Json.rawObjectOrString(response.body())));
        }
        if (segments.length == 6 && "metrics".equals(segments[4]) && "latest".equals(segments[5])) {
            if (!"GET".equals(method)) {
                return sendError(exchange, 405, "method not allowed");
            }
            return monitoring.latestMetricsJson(nodeId)
                    .map(json -> uncheckedJson(exchange, 200, json))
                    .orElseGet(() -> uncheckedError(exchange, 404, "no successful metrics sample is available"));
        }
        if (segments.length == 5 && "availability".equals(segments[4])) {
            if (!"GET".equals(method)) {
                return sendError(exchange, 405, "method not allowed");
            }
            return sendJson(exchange, 200, monitoring.availabilityJson(nodeId));
        }
        if (segments.length == 5 && "logs".equals(segments[4])) {
            if (!"GET".equals(method)) {
                return sendError(exchange, 405, "method not allowed");
            }
            int authorization = authorize(exchange);
            if (authorization != 0) {
                return authorization;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String unit = query.getOrDefault("unit", "");
            if (!SAFE_UNIT.matcher(unit).matches()) {
                return sendError(exchange, 400, "unit is required and contains unsupported characters");
            }
            int lines;
            try {
                lines = Integer.parseInt(query.getOrDefault("lines", "50"));
            } catch (NumberFormatException exception) {
                return sendError(exchange, 400, "lines must be an integer");
            }
            lines = Math.max(1, Math.min(200, lines));
            String agentPath = "/logs?unit=" + URLEncoder.encode(unit, StandardCharsets.UTF_8) + "&lines=" + lines;
            AgentResponse response = monitoring.proxyGet(nodeId, agentPath);
            return proxy(exchange, response);
        }
        if (segments.length == 5 && "actions".equals(segments[4])) {
            if (!"POST".equals(method)) {
                return sendError(exchange, 405, "method not allowed");
            }
            int authorization = authorize(exchange);
            if (authorization != 0) {
                return authorization;
            }
            String body = readBody(exchange);
            String trimmed = body.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return sendError(exchange, 400, "request body must be a JSON object");
            }
            return proxy(exchange, monitoring.proxyPost(nodeId, "/actions", body));
        }
        return sendError(exchange, 404, "route not found");
    }

    private int authorize(HttpExchange exchange) throws IOException {
        if (operatorToken.isBlank()) {
            return sendError(exchange, 503, "operator routes are disabled because OPERATOR_TOKEN is not configured");
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String supplied = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        boolean valid = MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), operatorToken.getBytes(StandardCharsets.UTF_8));
        return valid ? 0 : sendError(exchange, 401, "missing or invalid operator token");
    }

    private int proxy(HttpExchange exchange, AgentResponse response) throws IOException {
        if (!response.success()) {
            return sendAgentFailure(exchange, response);
        }
        return sendJson(exchange, 200, Json.rawObjectOrString(response.body()));
    }

    private int sendAgentFailure(HttpExchange exchange, AgentResponse response) throws IOException {
        return sendJson(exchange, 502, Json.object(
                "error", Json.string("Bad Gateway"),
                "message", Json.string(response.error() == null ? "agent request failed" : response.error()),
                "agentStatus", Json.number(response.statusCode()),
                "latencyMs", Json.number(response.latencyMs()),
                "agentResponse", Json.rawObjectOrString(response.body())));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        String rawLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (rawLength != null) {
            try {
                long length = Long.parseLong(rawLength);
                if (length <= 0 || length > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("request body must contain 1.." + MAX_BODY_BYTES + " bytes");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid Content-Length", exception);
            }
        }
        byte[] content = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (content.length == 0 || content.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("request body must contain 1.." + MAX_BODY_BYTES + " bytes");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.putIfAbsent(key, value);
        }
        return values;
    }

    private int sendError(HttpExchange exchange, int status, String message) throws IOException {
        return sendJson(exchange, status, Json.object(
                "error", Json.string(reason(status)),
                "message", Json.string(message == null ? "request failed" : message),
                "time", Json.string(Instant.now().toString())));
    }

    private int sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        return status;
    }

    private int uncheckedJson(HttpExchange exchange, int status, String json) {
        try {
            return sendJson(exchange, status, json);
        } catch (IOException exception) {
            throw new UncheckedExchangeException(exception);
        }
    }

    private int uncheckedError(HttpExchange exchange, int status, String message) {
        try {
            return sendError(exchange, status, message);
        } catch (IOException exception) {
            throw new UncheckedExchangeException(exception);
        }
    }

    private String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }

    @Override
    public void close() {
        server.stop(1);
    }

    private static final class UncheckedExchangeException extends RuntimeException {
        private UncheckedExchangeException(IOException cause) {
            super(cause);
        }
    }
}
