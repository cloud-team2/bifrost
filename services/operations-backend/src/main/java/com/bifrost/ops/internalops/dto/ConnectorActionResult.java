package com.bifrost.ops.internalops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kafka Connect connector mutation 결과. */
public record ConnectorActionResult(
        @JsonProperty("connector_name") String connectorName,
        String action,
        String status,
        String message
) {
    public static ConnectorActionResult accepted(String connectorName, String action) {
        return new ConnectorActionResult(connectorName, action, "SUCCESS", "accepted");
    }

    public static ConnectorActionResult replay(String connectorName, String action) {
        return new ConnectorActionResult(connectorName, action, "IDEMPOTENCY_REPLAY",
                "idempotent duplicate; mutation not re-executed");
    }
}
