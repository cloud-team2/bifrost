package com.bifrost.ops.provisioning.naming;

import java.util.UUID;

/**
 * Kafka 리소스(토픽·Connector CR·KafkaUser) 이름 생성 규칙의 단일 출처.
 *
 * <p>설계 §2 Provisioning의 네이밍 규칙을 코드로 고정한다. mapper(#12)·watcher(#13)·
 * mock provisioner(권세빈)·workspace KafkaUser provisioning(권세빈)이 모두 이 클래스를 통해
 * 동일한 이름을 만들도록 한다. 이름 규칙이 흩어지면 토픽 prefix / ACL / Connector CR이
 * 어긋나 프로젝트 격리가 깨지므로 한 곳에서만 정의한다.
 *
 * <h2>규칙 요약 (설계 §2.1, §3.2, §4.1)</h2>
 * <ul>
 *   <li><b>토픽 prefix</b>: {@code cdc.table.{projectKey}.{dbName}}
 *       — Debezium {@code topic.prefix}. Debezium이 {@code .{schema}.{table}}을 자동 부여.</li>
 *   <li><b>토픽 이름(table 중심)</b>: {@code cdc.table.{projectKey}.{dbName}.{schema}.{table}}
 *       — Sink connector {@code topics} 지정·조회에 사용.</li>
 *   <li><b>토픽 ACL prefix</b>: {@code cdc.table.{projectKey}.}
 *       — KafkaUser ACL이 이 prefix로 프로젝트 토픽 전체를 격리.</li>
 *   <li><b>KafkaUser</b>: {@code proj-{projectKey}-user} — 워크스페이스 단위 SCRAM principal.</li>
 *   <li><b>Source Connector CR</b>: {@code {pipelineId}-source} — 파이프라인 1개 = 단일 테이블.</li>
 *   <li><b>Sink Connector CR</b>: {@code {pipelineId}-sink} — CDC(direct)에서만 생성.</li>
 * </ul>
 *
 * <p>{@code projectKey}는 {@code workspace.project_key} 슬러그(소문자·숫자·하이픈)이고,
 * {@code dbName}/{@code schema}/{@code table}은 대상 DB의 실제 식별자다. 토픽/CR 이름은
 * 점({@code .})과 하이픈({@code -})으로 구성되며 Kafka topic naming과 K8s DNS-1123 규칙을
 * 만족하는 입력을 전제로 한다(검증은 호출부의 슬러그/식별자 검증에 위임).
 */
public final class ConnectorNaming {

    private static final String TOPIC_ROOT = "cdc.table";
    private static final String SOURCE_SUFFIX = "-source";
    private static final String SINK_SUFFIX = "-sink";

    private ConnectorNaming() {
    }

    /** Debezium {@code topic.prefix}: {@code cdc.table.{projectKey}.{dbName}}. */
    public static String topicPrefix(String projectKey, String dbName) {
        requireNotBlank(projectKey, "projectKey");
        requireNotBlank(dbName, "dbName");
        return TOPIC_ROOT + "." + projectKey + "." + dbName;
    }

    /** table 중심 토픽 이름: {@code cdc.table.{projectKey}.{dbName}.{schema}.{table}}. */
    public static String topicName(String projectKey, String dbName, String schema, String table) {
        requireNotBlank(schema, "schema");
        requireNotBlank(table, "table");
        return topicPrefix(projectKey, dbName) + "." + schema + "." + table;
    }

    /** KafkaUser ACL prefix(끝에 {@code .} 포함): {@code cdc.table.{projectKey}.}. */
    public static String topicAclPrefix(String projectKey) {
        requireNotBlank(projectKey, "projectKey");
        return TOPIC_ROOT + "." + projectKey + ".";
    }

    /** 워크스페이스 KafkaUser principal: {@code proj-{projectKey}-user}. */
    public static String kafkaUserName(String projectKey) {
        requireNotBlank(projectKey, "projectKey");
        return "proj-" + projectKey + "-user";
    }

    /** Source(Debezium) KafkaConnector CR 이름: {@code {pipelineId}-source}. */
    public static String sourceConnectorName(UUID pipelineId) {
        requireNotNull(pipelineId);
        return pipelineId + SOURCE_SUFFIX;
    }

    /** Sink(JDBC) KafkaConnector CR 이름: {@code {pipelineId}-sink}. */
    public static String sinkConnectorName(UUID pipelineId) {
        requireNotNull(pipelineId);
        return pipelineId + SINK_SUFFIX;
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNotNull(UUID pipelineId) {
        if (pipelineId == null) {
            throw new IllegalArgumentException("pipelineId must not be null");
        }
    }
}
