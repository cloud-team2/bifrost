package com.bifrost.ops.internalops.dto;

import java.util.List;
import java.util.Map;

public record LogSearchResult(List<Map<String, Object>> logs, int total, String note) {
    public static LogSearchResult of(List<Map<String, Object>> logs) {
        return new LogSearchResult(logs, logs.size(), null);
    }

    public static LogSearchResult stub() {
        return new LogSearchResult(List.of(), 0, "log source pending — dependency on monitoring integration");
    }
}
