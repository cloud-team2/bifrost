package com.bifrost.ops.internalops.dto;

public record ReadyResult(String status, String db) {
    public static ReadyResult ok()   { return new ReadyResult("READY", "UP"); }
    public static ReadyResult degraded() { return new ReadyResult("DEGRADED", "DOWN"); }
}
