package com.bifrost.ops.internalops.dto;

import java.util.List;

public record ConnectorStatusListResult(
        List<ConnectorStatusSummary> connectors
) {
    public ConnectorStatusListResult {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
    }

    public record ConnectorStatusSummary(
            String connector,
            String type,
            String status,
            Integer tasksRunning,
            Integer tasksTotal,
            Double throughputPerSecond,
            String error
    ) {}
}
