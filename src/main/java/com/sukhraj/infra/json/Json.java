package com.sukhraj.infra.json;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Small JSON writer for the controller's fixed response models. */
public final class Json {
    private Json() {}

    public static String string(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    public static String number(long value) {
        return Long.toString(value);
    }

    public static String decimal(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "null";
    }

    public static String bool(boolean value) {
        return Boolean.toString(value);
    }

    public static String nullable(String value) {
        return value == null ? "null" : string(value);
    }

    public static String rawObjectOrString(String value) {
        if (value == null) {
            return "null";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed;
        }
        return string(value);
    }

    public static String object(Map<String, String> encodedValues) {
        return encodedValues.entrySet().stream()
                .map(entry -> string(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static String object(Object... keyAndEncodedValuePairs) {
        if (keyAndEncodedValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("JSON object requires key/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < keyAndEncodedValuePairs.length; index += 2) {
            values.put((String) keyAndEncodedValuePairs[index], (String) keyAndEncodedValuePairs[index + 1]);
        }
        return object(values);
    }

    public static String array(Collection<String> encodedValues) {
        return encodedValues.stream().collect(Collectors.joining(",", "[", "]"));
    }

    public static String escape(String value) {
        StringBuilder output = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        return output.toString();
    }
}
