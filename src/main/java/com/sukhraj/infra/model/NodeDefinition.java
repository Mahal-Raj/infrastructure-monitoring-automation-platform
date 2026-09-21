package com.sukhraj.infra.model;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

public record NodeDefinition(String id, String name, URI baseUri, String token) {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public NodeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseUri, "baseUri");
        token = token == null ? "" : token;
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Node id must match " + SAFE_ID.pattern());
        }
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Node name must contain 1..120 characters");
        }
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalArgumentException("Node URL must use HTTP or HTTPS");
        }
        if (baseUri.getHost() == null) {
            throw new IllegalArgumentException("Node URL must include a host");
        }
        String normalized = baseUri.toString();
        if (normalized.endsWith("/")) {
            baseUri = URI.create(normalized.substring(0, normalized.length() - 1));
        }
    }
}
