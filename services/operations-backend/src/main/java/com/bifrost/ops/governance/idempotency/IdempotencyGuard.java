package com.bifrost.ops.governance.idempotency;

import com.bifrost.ops.governance.idempotency.persistence.entity.IdempotencyKeyEntity;
import com.bifrost.ops.governance.idempotency.persistence.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 동일 idempotency_key 중복 실행을 방지한다(S3). */
@Service
public class IdempotencyGuard {

    private static final long TTL_HOURS = 24L;

    private final IdempotencyKeyRepository repository;

    public IdempotencyGuard(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * key를 확인한다. DONE 상태면 캐시된 result 반환, 미존재면 PROCESSING으로 등록 후 empty 반환.
     * @return 이미 완료된 결과(중복 요청), 없으면 empty(최초 요청 → 실행 진행)
     */
    @Transactional
    public Optional<String> check(String key, UUID tenantId) {
        Optional<IdempotencyKeyEntity> existing = repository.findByIdemKeyAndTenantId(key, tenantId);
        if (existing.isPresent()) {
            IdempotencyKeyEntity e = existing.get();
            if ("DONE".equals(e.getStatus())) return Optional.ofNullable(e.getResult());
            // PROCESSING: 진행 중인 요청 → 중복으로 처리
            return Optional.of("{\"status\":\"PROCESSING\"}");
        }
        // 최초 요청 → PROCESSING 등록
        IdempotencyKeyEntity e = new IdempotencyKeyEntity();
        e.setIdemKey(key);
        e.setTenantId(tenantId);
        e.setStatus("PROCESSING");
        e.setExpiresAt(Instant.now().plusSeconds(TTL_HOURS * 3600));
        repository.save(e);
        return Optional.empty();
    }

    @Transactional
    public void complete(String key, UUID tenantId, String result) {
        repository.findByIdemKeyAndTenantId(key, tenantId).ifPresent(e -> {
            e.setStatus("DONE");
            e.setResult(result);
            repository.save(e);
        });
    }
}
