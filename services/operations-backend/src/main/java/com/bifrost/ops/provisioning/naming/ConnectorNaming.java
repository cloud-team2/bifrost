package com.bifrost.ops.provisioning.naming;

import com.bifrost.ops.provisioning.dto.PipelinePattern;

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
 *   <li><b>토픽 base prefix</b>: {@code {root}.{projectKey}.{dbSlug}}
 *       — {@code dbSlug = dbName-datasourceIdPrefix}. project 범위 조회·ACL prefix의 기준.</li>
 *   <li><b>토픽 이름(table 중심)</b>: {@code {root}.{projectKey}.{dbSlug}.{schema}.{table}}
 *       — Source의 Debezium {@code topic.prefix}와 Sink connector {@code topics} 지정·조회에 사용.
 *       Source mapper는 Debezium이 덧붙이는 {@code .{schema}.{table}} suffix를 route SMT로 제거한다(#365).</li>
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

    private static final String TOPIC_ROOT_CDC = "cdc.table";
    private static final String TOPIC_ROOT_EDA = "eda.table";
    private static final String SOURCE_SUFFIX = "-source";
    private static final String SINK_SUFFIX = "-sink";

    private ConnectorNaming() {
    }

    /** 패턴별 토픽 root: CDC(DIRECT)=cdc.table, EDA(FAN_OUT)=eda.table (#447). */
    public static String topicRoot(PipelinePattern pattern) {
        requireNotNull(pattern, "pattern");
        return pattern == PipelinePattern.FAN_OUT ? TOPIC_ROOT_EDA : TOPIC_ROOT_CDC;
    }

    /**
     * 토픽 base prefix: {@code {root}.{projectKey}.{dbSlug}} ({@code root}는 패턴별, #447).
     * Source connector의 Debezium {@code topic.prefix}는 최종 토픽명인 {@link #topicName}을 사용한다(#365).
     *
     * <p>{@code dbSlug = {dbName}-{datasourceId 앞 8 hex}}. 표시 이름({@code dbName})은
     * datasource 등록 시 사용자가 지은 이름이라 서로 다른 물리 DB라도 같을 수 있어, 같은 프로젝트·
     * 테이블이면 토픽이 충돌했다(#265). datasource 고유 id를 슬러그에 섞어 충돌을 막는다.
     */
    public static String topicPrefix(PipelinePattern pattern, String projectKey, String dbName, UUID datasourceId) {
        requireNotBlank(projectKey, "projectKey");
        requireNotBlank(dbName, "dbName");
        requireNotNull(datasourceId, "datasourceId");
        return topicRoot(pattern) + "." + projectKey + "." + datasourceSlug(dbName, datasourceId);
    }

    /** table 중심 토픽 이름: {@code {root}.{projectKey}.{dbSlug}.{schema}.{table}}. */
    public static String topicName(PipelinePattern pattern, String projectKey, String dbName,
                                   UUID datasourceId, String schema, String table) {
        requireNotBlank(schema, "schema");
        requireNotBlank(table, "table");
        return topicPrefix(pattern, projectKey, dbName, datasourceId) + "." + schema + "." + table;
    }

    /** datasource 식별 슬러그: {@code {dbName}-{datasourceId 앞 8 hex}} — 표시 이름 충돌 방지(#265). */
    public static String datasourceSlug(String dbName, UUID datasourceId) {
        requireNotBlank(dbName, "dbName");
        requireNotNull(datasourceId, "datasourceId");
        return dbName + "-" + datasourceId.toString().substring(0, 8);
    }

    /**
     * KafkaUser ACL prefix(끝에 {@code .} 포함): {@code cdc.table.{projectKey}.}.
     * <p>주의(#447): EDA 토픽은 {@code eda.table.*}를 쓰므로, authorizer를 켜서 ACL을 강제한다면
     * {@code eda.table.{projectKey}.*}도 함께 부여해야 한다(현재 cluster는 authorizer 미활성으로 미강제).
     */
    public static String topicAclPrefix(String projectKey) {
        requireNotBlank(projectKey, "projectKey");
        return TOPIC_ROOT_CDC + "." + projectKey + ".";
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
        requireNotNull(pipelineId, "pipelineId");
    }

    private static void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }
}
