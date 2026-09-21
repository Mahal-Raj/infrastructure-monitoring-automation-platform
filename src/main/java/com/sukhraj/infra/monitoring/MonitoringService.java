package com.sukhraj.infra.monitoring;

import com.sukhraj.infra.client.AgentClient;
import com.sukhraj.infra.client.AgentClient.AgentResponse;
import com.sukhraj.infra.json.Json;
import com.sukhraj.infra.model.NodeDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MonitoringService implements AutoCloseable {
    private final Map<String, NodeState> states;
    private final AgentClient client;
    private final Duration pollInterval;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService pollers;
    private final AtomicBoolean started = new AtomicBoolean();

    public MonitoringService(
            List<NodeDefinition> nodes,
            AgentClient client,
            Duration pollInterval,
            int availabilityWindow) {
        this.client = client;
        this.pollInterval = pollInterval;
        Map<String, NodeState> configured = new LinkedHashMap<>();
        for (NodeDefinition node : nodes) {
            if (configured.put(node.id(), new NodeState(node, availabilityWindow)) != null) {
                throw new IllegalArgumentException("Duplicate node id: " + node.id());
            }
        }
        this.states = Map.copyOf(configured);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "monitoring-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.pollers = Executors.newFixedThreadPool(Math.min(8, Math.max(1, nodes.size())), runnable -> {
            Thread thread = new Thread(runnable, "node-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::pollAll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollAll() {
        for (NodeState state : sortedStates()) {
            pollers.submit(() -> collect(state.node().id()));
        }
    }

    public AgentResponse collect(String nodeId) {
        NodeState state = requireState(nodeId);
        AgentResponse response = client.get(state.node(), "/metrics");
        state.record(response);
        return response;
    }

    public AgentResponse proxyGet(String nodeId, String pathAndQuery) {
        return client.get(requireState(nodeId).node(), pathAndQuery);
    }

    public AgentResponse proxyPost(String nodeId, String pathAndQuery, String body) {
        return client.post(requireState(nodeId).node(), pathAndQuery, body);
    }

    public boolean contains(String nodeId) {
        return states.containsKey(nodeId);
    }

    public int nodeCount() {
        return states.size();
    }

    public String nodesJson() {
        List<String> nodes = sortedStates().stream().map(NodeState::summaryJson).toList();
        return Json.object(
                "generatedAt", Json.string(Instant.now().toString()),
                "count", Json.number(nodes.size()),
                "nodes", Json.array(nodes));
    }

    public Optional<String> latestMetricsJson(String nodeId) {
        return Optional.ofNullable(requireState(nodeId).latestMetricsJson());
    }

    public String availabilityJson(String nodeId) {
        return requireState(nodeId).availabilityJson();
    }

    public String reportJson() {
        List<String> nodeReports = new ArrayList<>();
        for (NodeState state : sortedStates()) {
            String latest = state.latestMetricsJson();
            nodeReports.add(Json.object(
                    "status", state.summaryJson(),
                    "availability", state.availabilityJson(),
                    "latestMetrics", latest == null ? "null" : latest));
        }
        return Json.object(
                "reportType", Json.string("infrastructure-operations"),
                "generatedAt", Json.string(Instant.now().toString()),
                "nodeCount", Json.number(nodeReports.size()),
                "nodes", Json.array(nodeReports));
    }

    private NodeState requireState(String nodeId) {
        NodeState state = states.get(nodeId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown node: " + nodeId);
        }
        return state;
    }

    private List<NodeState> sortedStates() {
        return states.values().stream()
                .sorted(Comparator.comparing(state -> state.node().id()))
                .toList();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        pollers.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            pollers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
