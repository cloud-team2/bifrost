package com.bifrost.ops.internalops.safeinject;

import java.util.Locale;

public enum SafeInjectionFault {
    AUTH("auth", "SINK_AUTH_EXPIRED", "a1"),
    SCHEMA("schema", "SCHEMA_MISMATCH", "s1"),
    LAG("lag", "CONSUMER_LAG_SPIKE", "l1"),
    SINK_FAIL("sink-fail", "SINK_DB_CONNECTION_TIMEOUT", "f1"),
    NO_FAULT("no-fault", "UNKNOWN_WITH_EVIDENCE_GAP", "n1");

    private final String wireName;
    private final String expectedRootCauseId;
    private final String code;

    SafeInjectionFault(String wireName, String expectedRootCauseId, String code) {
        this.wireName = wireName;
        this.expectedRootCauseId = expectedRootCauseId;
        this.code = code;
    }

    public String wireName() {
        return wireName;
    }

    public String expectedRootCauseId() {
        return expectedRootCauseId;
    }

    public String code() {
        return code;
    }

    public static SafeInjectionFault parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("fault must not be blank");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SafeInjectionFault fault : values()) {
            if (fault.wireName.equals(normalized)) {
                return fault;
            }
        }
        throw new IllegalArgumentException("unsupported safe injection fault: " + raw);
    }
}
