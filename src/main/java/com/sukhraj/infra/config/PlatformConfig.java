package com.sukhraj.infra.config;

import com.sukhraj.infra.model.NodeDefinition;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PlatformConfig(
        String bindAddress,
        int port,
        Duration pollInterval,
        Duration requestTimeout,
        int availabilityWindow,
        String operatorToken,
        List<NodeDefinition> nodes) {

    public PlatformConfig {
        nodes = List.copyOf(nodes);
        operatorToken = operatorToken == null ? "" : operatorToken;
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress is required");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (availabilityWindow < 1 || availabilityWindow > 10_000) {
            throw new IllegalArgumentException("availabilityWindow must be between 1 and 10000");
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("At least one node must be configured");
        }
    }

    public static PlatformConfig fromEnvironment() throws IOException {
        Map<String, String> environment = System.getenv();
        Path inventory = Path.of(environment.getOrDefault("NODE_CONFIG", "config/nodes.local.csv"));
        return new PlatformConfig(
                environment.getOrDefault("PLATFORM_BIND", "127.0.0.1"),
                integer(environment, "PLATFORM_PORT", 8080, 1, 65_535),
                Duration.ofSeconds(integer(environment, "POLL_INTERVAL_SECONDS", 30, 1, 86_400)),
                Duration.ofSeconds(integer(environment, "REQUEST_TIMEOUT_SECONDS", 5, 1, 60)),
                integer(environment, "AVAILABILITY_WINDOW", 120, 1, 10_000),
                environment.getOrDefault("OPERATOR_TOKEN", ""),
                loadNodes(inventory, environment));
    }

    static List<NodeDefinition> loadNodes(Path inventory, Map<String, String> environment) throws IOException {
        if (!Files.isRegularFile(inventory)) {
            throw new IllegalArgumentException("Node inventory does not exist: " + inventory.toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(inventory);
        List<NodeDefinition> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        boolean headerSeen = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> fields = csv(line);
            if (!headerSeen) {
                headerSeen = true;
                if (!fields.equals(List.of("id", "name", "baseUrl", "tokenEnv"))) {
                    throw new IllegalArgumentException("Unexpected inventory header in " + inventory);
                }
                continue;
            }
            if (fields.size() != 4) {
                throw new IllegalArgumentException("Invalid inventory row " + (index + 1) + ": expected four columns");
            }
            String id = fields.get(0);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate node id: " + id);
            }
            String tokenVariable = fields.get(3);
            String token = tokenVariable.isBlank() ? "" : environment.getOrDefault(tokenVariable, "");
            nodes.add(new NodeDefinition(id, fields.get(1), URI.create(fields.get(2)), token));
        }
        return nodes;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue, int minimum, int maximum) {
        String raw = environment.get(name);
        int value;
        try {
            value = raw == null ? defaultValue : Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    static List<String> csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '\"') {
                    current.append('\"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted CSV field");
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
