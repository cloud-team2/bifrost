package com.bifrost.ops.internalops.dto;

public record HealthResult(String status) {
    public static HealthResult up() { return new HealthResult("UP"); }
}
