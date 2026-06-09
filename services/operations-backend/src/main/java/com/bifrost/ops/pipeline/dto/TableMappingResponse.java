package com.bifrost.ops.pipeline.dto;

import java.util.List;
import java.util.UUID;

/** Debezium/CDC connector config에서 추출한 source table → Kafka topic → sink table 매핑(#303). */
public record TableMappingResponse(
        UUID pipelineId,
        String sourceConnector,
        String sinkConnector,
        List<TableMappingEntry> mappings) {

    public record TableMappingEntry(
            String sourceTable,
            String kafkaTopic,
            String sinkTable) {
    }
}
