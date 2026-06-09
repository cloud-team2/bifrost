package com.bifrost.ops.provisioning.dto;

import com.bifrost.ops.global.common.datasource.DbType;

import java.util.UUID;

/**
 * 파이프라인 리소스 생성 요청(provisioner 입력).
 *
 * <p>설계 §2.1의 안정 계약: API 계약(command/result)을 고정해 mock 구현(권세빈)과
 * real 구현(백강민, {@code provisioning.impl.strimzi})이 같은 입력을 받게 한다.
 *
 * <p>{@code source}는 항상 필수, {@code sink}는 {@link PipelinePattern#DIRECT}(CDC)에서만 존재한다.
 * 자격증명은 평문이 아니라 {@code secretRef}로만 전달되며, real provisioner가 생성 시점에
 * {@code SecretStore.resolve}로 해석한다(설계 §2 4.1, 자격증명 노출면 최소화).
 *
 * @param pipelineId 파이프라인 id(Connector CR 이름 기준, {@code ConnectorNaming})
 * @param projectKey 워크스페이스 슬러그(토픽 prefix·ACL 기준)
 * @param pattern    {@code FAN_OUT}(EDA) / {@code DIRECT}(CDC)
 * @param source     source DB 엔드포인트(필수)
 * @param sink       sink DB 엔드포인트(CDC만, EDA는 null)
 */
public record PipelineProvisionCommand(
        UUID pipelineId,
        String projectKey,
        PipelinePattern pattern,
        Endpoint source,
        Endpoint sink
) {

    public PipelineProvisionCommand {
        if (pipelineId == null) {
            throw new IllegalArgumentException("pipelineId must not be null");
        }
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (pattern == PipelinePattern.DIRECT && sink == null) {
            throw new IllegalArgumentException("DIRECT(CDC) pattern requires sink");
        }
        if (pattern == PipelinePattern.FAN_OUT && sink != null) {
            throw new IllegalArgumentException("FAN_OUT(EDA) pattern must not have sink");
        }
    }

    /**
     * DB 엔드포인트. 자격증명은 {@code secretRef}로만 참조한다.
     *
     * @param engine       DB 엔진(connector class 선택 기준)
     * @param host         DB 호스트
     * @param port         DB 포트
     * @param dbName       DB 이름(토픽 prefix 구성)
     * @param datasourceId datasource 고유 id(토픽 슬러그에 섞어 표시 이름 충돌 방지, #265)
     * @param schema       스키마(단일 테이블). sink는 미사용 가능(null)
     * @param table        테이블(단일 테이블). sink는 미사용 가능(null)
     * @param secretRef    SecretStore 참조(평문 금지)
     */
    public record Endpoint(
            DbType engine,
            String host,
            int port,
            String dbName,
            UUID datasourceId,
            String schema,
            String table,
            String secretRef
    ) {
        public Endpoint {
            if (engine == null) {
                throw new IllegalArgumentException("engine must not be null");
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (dbName == null || dbName.isBlank()) {
                throw new IllegalArgumentException("dbName must not be blank");
            }
            if (datasourceId == null) {
                throw new IllegalArgumentException("datasourceId must not be null");
            }
            if (secretRef == null || secretRef.isBlank()) {
                throw new IllegalArgumentException("secretRef must not be blank");
            }
        }
    }
}
