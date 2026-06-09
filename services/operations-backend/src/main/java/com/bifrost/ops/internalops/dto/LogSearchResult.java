package com.bifrost.ops.internalops.dto;

import java.util.List;

public record LogSearchResult(List<Object> logs, int total, String note) {
    public static LogSearchResult stub() {
        return new LogSearchResult(List.of(), 0, "log source pending — dependency on monitoring integration");
    }
}
