package com.bifrost.ops.provisioning.persistence;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * {@link ConnectorStatusSink}의 JPA 구현(#46): watch로 받은 connector 상태를 {@code connectors}
 * 행에 갱신한다.
 *
 * <p>{@code cr_name}으로 행을 찾아 {@code state}/{@code last_error}/{@code updated_at}만 쓴다.
 * 행 생성은 provisioner(생성 경로)가 담당하므로, 아직 행이 없으면(예: 생성 직후 watch가 먼저 도달)
 * 경고만 남기고 건너뛴다. 멱등 — 같은 상태가 반복 통지돼도 동일 값으로 덮어쓴다.
 */
@Component
public class ConnectorStatusRepositoryAdapter implements ConnectorStatusSink {

    private static final Logger log = LoggerFactory.getLogger(ConnectorStatusRepositoryAdapter.class);

    private final ConnectorRepository connectorRepository;

    public ConnectorStatusRepositoryAdapter(ConnectorRepository connectorRepository) {
        this.connectorRepository = connectorRepository;
    }

    @Override
    @Transactional
    public void record(ConnectorStatusUpdate update) {
        connectorRepository.findByCrName(update.connectorName()).ifPresentOrElse(
                entity -> apply(entity, update),
                () -> log.warn("connectors 행 없음, 상태 반영 skip: name={}", update.connectorName()));
    }

    private void apply(ConnectorEntity entity, ConnectorStatusUpdate update) {
        entity.setState(update.connectorState().name());
        entity.setLastError(update.lastError());
        entity.setUpdatedAt(Instant.now());
        connectorRepository.save(entity);
        log.debug("connector 상태 반영: name={}, state={}, failedTasks={}/{}",
                update.connectorName(), update.connectorState(),
                update.failedTasks(), update.totalTasks());
    }
}
