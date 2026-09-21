package com.sukhraj.infra.monitoring;

import com.sukhraj.infra.client.AgentClient.AgentResponse;
import com.sukhraj.infra.json.Json;
import com.sukhraj.infra.model.NodeDefinition;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class NodeState {
    private final NodeDefinition node;
    private final int windowSize;
    private final Deque<Boolean> samples = new ArrayDeque<>();
    private Instant lastAttempt;
    private Instant lastSuccess;
    private boolean online;
    private int consecutiveFailures;
    private String latestMetrics;
    private long latestLatencyMs;
    private String latestError;

    NodeState(NodeDefinition node, int windowSize) {
        this.node = node;
        this.windowSize = windowSize;
    }

    NodeDefinition node() {
        return node;
    }

    synchronized void record(AgentResponse response) {
        lastAttempt = Instant.now();
        online = response.success();
        latestLatencyMs = response.latencyMs();
        latestError = response.error();
        samples.addLast(response.success());
        while (samples.size() > windowSize) {
            samples.removeFirst();
        }
        if (response.success()) {
            latestMetrics = response.body();
            lastSuccess = lastAttempt;
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
        }
    }

    synchronized String summaryJson() {
        return Json.object(
                "id", Json.string(node.id()),
                "name", Json.string(node.name()),
                "baseUrl", Json.string(node.baseUri().toString()),
                "online", Json.bool(online),
                "lastAttemptAt", Json.nullable(lastAttempt == null ? null : lastAttempt.toString()),
                "lastSuccessAt", Json.nullable(lastSuccess == null ? null : lastSuccess.toString()),
                "latestLatencyMs", Json.number(latestLatencyMs),
                "consecutiveFailures", Json.number(consecutiveFailures),
                "availabilityPercent", Json.decimal(availabilityPercent()),
                "samples", Json.number(samples.size()),
                "latestError", Json.nullable(latestError));
    }

    synchronized String availabilityJson() {
        int successful = (int) samples.stream().filter(Boolean::booleanValue).count();
        List<String> history = new ArrayList<>(samples.size());
        for (boolean sample : samples) {
            history.add(Json.bool(sample));
        }
        return Json.object(
                "nodeId", Json.string(node.id()),
                "online", Json.bool(online),
                "availabilityPercent", Json.decimal(availabilityPercent()),
                "successfulSamples", Json.number(successful),
                "totalSamples", Json.number(samples.size()),
                "consecutiveFailures", Json.number(consecutiveFailures),
                "lastAttemptAt", Json.nullable(lastAttempt == null ? null : lastAttempt.toString()),
                "lastSuccessAt", Json.nullable(lastSuccess == null ? null : lastSuccess.toString()),
                "history", Json.array(history));
    }

    synchronized String latestMetricsJson() {
        if (latestMetrics == null) {
            return null;
        }
        return Json.object(
                "nodeId", Json.string(node.id()),
                "lastSuccessAt", Json.string(lastSuccess.toString()),
                "latencyMs", Json.number(latestLatencyMs),
                "metrics", Json.rawObjectOrString(latestMetrics));
    }

    private double availabilityPercent() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        long successful = samples.stream().filter(Boolean::booleanValue).count();
        return successful * 100.0 / samples.size();
    }
}
