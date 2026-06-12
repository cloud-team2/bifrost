package com.bifrost.ops.incident;

import java.net.URI;
import java.util.Locale;

final class AiServiceEndpoint {

    private final String baseUrl;
    private final String disabledReason;

    private AiServiceEndpoint(String baseUrl, String disabledReason) {
        this.baseUrl = baseUrl;
        this.disabledReason = disabledReason;
    }

    static AiServiceEndpoint from(String value) {
        if (value == null || value.isBlank()) {
            return disabled("AI_SERVICE_URL is not configured");
        }
        String normalized = stripTrailingSlash(value.strip());
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            return disabled("ai-service.url must be an absolute URL");
        }
        String host = uri.getHost();
        if (uri.getScheme() == null || host == null) {
            return disabled("ai-service.url must be an absolute URL");
        }
        if (isLocalhost(host)) {
            return disabled("ai-service.url points to localhost; set AI_SERVICE_URL for this deployment");
        }
        return new AiServiceEndpoint(normalized, null);
    }

    boolean configured() {
        return disabledReason == null;
    }

    String baseUrl() {
        return baseUrl;
    }

    String disabledReason() {
        return disabledReason;
    }

    private static AiServiceEndpoint disabled(String reason) {
        return new AiServiceEndpoint(null, reason);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isLocalhost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1");
    }
}
