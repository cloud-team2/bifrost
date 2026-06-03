package com.bifrost.ops.provisioning.persistence.repository;

import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * connector 메타데이터 저장소(설계 §4 3.5).
 *
 * <p>provisioner(#12)가 생성 후 행을 기록하고, watcher 상태 adapter(#46)가 {@code cr_name}으로
 * 찾아 {@code state}/{@code lastError}/{@code updatedAt}를 갱신한다.
 */
public interface ConnectorRepository extends JpaRepository<ConnectorEntity, UUID> {

    Optional<ConnectorEntity> findByCrName(String crName);

    List<ConnectorEntity> findByPipelineId(UUID pipelineId);
}
