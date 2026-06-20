package com.bifrost.ops.incident.dto;

import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentResponseTest {

    private static final List<String> INCIDENT_FIELDS = List.of(
            "id",
            "tenantId",
            "groupingKey",
            "severity",
            "severityReason",
            "alertRoute",
            "status",
            "title",
            "rca",
            "sourceType",
            "sourceId",
            "openedAt",
            "resolvedAt");

    @Test
    void fromEntitySerializesAllIncidentFieldsForSsePayloads() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant openedAt = Instant.parse("2026-06-09T00:00:00Z");
        Instant resolvedAt = Instant.parse("2026-06-09T00:05:00Z");

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setGroupingKey("connector:orders");
        entity.setSeverity("ERROR");
        entity.setSeverityReason("impact=user_sli:data_freshness; urgency=page");
        entity.setAlertRoute("PAGE");
        entity.setStatus("RESOLVED");
        entity.setTitle("Orders connector failed");
        entity.setRca("restart connector");
        entity.setSourceType("CONNECTOR");
        entity.setSourceId(sourceId);
        entity.setOpenedAt(openedAt);
        entity.setResolvedAt(resolvedAt);

        JsonNode json = toJson(IncidentResponse.from(entity));

        assertHasIncidentFields(json);
        assertThat(json.get("id").asText()).isEqualTo(id.toString());
        assertThat(json.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(json.get("groupingKey").asText()).isEqualTo("connector:orders");
        assertThat(json.get("severity").asText()).isEqualTo("ERROR");
        assertThat(json.get("severityReason").asText()).isEqualTo("impact=user_sli:data_freshness; urgency=page");
        assertThat(json.get("alertRoute").asText()).isEqualTo("PAGE");
        assertThat(json.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(json.get("title").asText()).isEqualTo("Orders connector failed");
        assertThat(json.get("rca").asText()).isEqualTo("restart connector");
        assertThat(json.get("sourceType").asText()).isEqualTo("CONNECTOR");
        assertThat(json.get("sourceId").asText()).isEqualTo(sourceId.toString());
        assertThat(json.get("openedAt").asText()).isEqualTo("2026-06-09T00:00:00Z");
        assertThat(json.get("resolvedAt").asText()).isEqualTo("2026-06-09T00:05:00Z");
    }

    @Test
    void fromOpenEntitySerializesNullableFieldsAsPresentNulls() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant openedAt = Instant.parse("2026-06-09T00:00:00Z");

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setGroupingKey("connector:orders");
        entity.setSeverity("ERROR");
        entity.setStatus("OPEN");
        entity.setTitle("Orders connector failed");
        entity.setOpenedAt(openedAt);

        JsonNode json = toJson(IncidentResponse.from(entity));

        assertHasIncidentFields(json);
        assertThat(json.get("id").asText()).isEqualTo(id.toString());
        assertThat(json.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(json.get("status").asText()).isEqualTo("OPEN");
        assertThat(json.get("openedAt").asText()).isEqualTo("2026-06-09T00:00:00Z");
        assertThat(json.get("rca").isNull()).isTrue();
        assertThat(json.get("severityReason").isNull()).isTrue();
        assertThat(json.get("alertRoute").isNull()).isTrue();
        assertThat(json.get("sourceType").isNull()).isTrue();
        assertThat(json.get("sourceId").isNull()).isTrue();
        assertThat(json.get("resolvedAt").isNull()).isTrue();
    }

    private static JsonNode toJson(IncidentResponse response) {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build()
                .valueToTree(response);
    }

    private static void assertHasIncidentFields(JsonNode json) {
        assertThat(fieldNames(json)).containsExactlyInAnyOrderElementsOf(INCIDENT_FIELDS);
        assertThat(json).hasSize(INCIDENT_FIELDS.size());
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = json.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }
}
