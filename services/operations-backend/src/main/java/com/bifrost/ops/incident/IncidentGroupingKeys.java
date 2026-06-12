package com.bifrost.ops.incident;

import java.util.UUID;

/** Shared incident grouping keys used by independent monitoring writers. */
public final class IncidentGroupingKeys {

    private IncidentGroupingKeys() {
    }

    public static String pipelineAvailability(UUID pipelineId) {
        return "pipeline:" + pipelineId + ":availability";
    }

    public static String datasource(UUID datasourceId) {
        return "datasource:" + datasourceId;
    }

    public static String connectorWorker(String connectorName) {
        return "connector:" + connectorName;
    }

    public static String consumerLag(String group) {
        return "consumer-lag:" + group;
    }

    /** 토픽 복제 헬스(under-replicated·offline 파티션, #633 Phase 2). */
    public static String topicReplication(String topic) {
        return "topic:" + topic + ":replication";
    }
}
