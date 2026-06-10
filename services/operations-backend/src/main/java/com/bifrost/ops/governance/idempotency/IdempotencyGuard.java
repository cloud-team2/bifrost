package com.bifrost.ops.governance.idempotency;

import com.bifrost.ops.governance.idempotency.persistence.entity.IdempotencyKeyEntity;
import com.bifrost.ops.governance.idempotency.persistence.repository.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 동일 idempotency_key 중복 실행을 방지한다(S3). */
@Service
public class IdempotencyGuard {

    private static final long TTL_HOURS = 24L;
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String RESPONSE_OK = "OK";
    public static final String RESPONSE_ERROR = "ERROR";

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
        CheckResult result = check(key, tenantId, null, null);
        if (result.kind() == CheckKind.NEW) return Optional.empty();
        if (result.kind() == CheckKind.PROCESSING) return Optional.of("{\"status\":\"PROCESSING\"}");
        return Optional.ofNullable(result.result());
    }

    /**
     * key+tenant의 중복을 확인하되 operation/params_hash가 다르면 replay하지 않고 충돌로 반환한다.
     */
    @Transactional
    public CheckResult check(String key, UUID tenantId, String operation, String paramsHash) {
        Optional<IdempotencyKeyEntity> existing = repository.findByIdemKeyAndTenantId(key, tenantId);
        if (existing.isPresent()) {
            return existing(existing.get(), operation, paramsHash);
        }

        return createProcessing(key, tenantId, operation, paramsHash);
    }

    private CheckResult createProcessing(String key, UUID tenantId, String operation, String paramsHash) {
        IdempotencyKeyEntity e = new IdempotencyKeyEntity();
        e.setIdemKey(key);
        e.setTenantId(tenantId);
        e.setStatus(STATUS_PROCESSING);
        e.setOperation(operation);
        e.setParamsHash(paramsHash);
        e.setExpiresAt(Instant.now().plusSeconds(TTL_HOURS * 3600));
        try {
            repository.saveAndFlush(e);
            return CheckResult.created();
        } catch (DataIntegrityViolationException race) {
            return repository.findByIdemKeyAndTenantId(key, tenantId)
                    .map(row -> existing(row, operation, paramsHash))
                    .orElseThrow(() -> race);
        }
    }

    private CheckResult existing(IdempotencyKeyEntity e, String operation, String paramsHash) {
        if (e.getExpiresAt() != null && e.getExpiresAt().isBefore(Instant.now())) {
            repository.delete(e);
            repository.flush();
            return createProcessing(e.getIdemKey(), e.getTenantId(), operation, paramsHash);
        }
        if (isScopeMismatch(e, operation, paramsHash)) {
            return CheckResult.conflict();
        }
        if (STATUS_DONE.equals(e.getStatus())) {
            return CheckResult.duplicate(e.getResult(), e.getResponseStatus(), e.getHttpStatus(), e.getApprovalId());
        }
        return CheckResult.processing();
    }

    private static boolean isScopeMismatch(IdempotencyKeyEntity e, String operation, String paramsHash) {
        if (operation == null && paramsHash == null) return false;
        return !sameNullable(operation, e.getOperation()) || !sameNullable(paramsHash, e.getParamsHash());
    }

    private static boolean sameNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Transactional
    public void complete(String key, UUID tenantId, String result) {
        complete(key, tenantId, result, RESPONSE_OK, 200);
    }

    @Transactional
    public void complete(String key, UUID tenantId, String result, String responseStatus, Integer httpStatus) {
        complete(key, tenantId, result, responseStatus, httpStatus, null);
    }

    @Transactional
    public void complete(String key,
                         UUID tenantId,
                         String result,
                         String responseStatus,
                         Integer httpStatus,
                         UUID approvalId) {
        repository.findByIdemKeyAndTenantId(key, tenantId).ifPresent(e -> {
            e.setStatus(STATUS_DONE);
            e.setResult(result);
            e.setResponseStatus(responseStatus);
            e.setHttpStatus(httpStatus);
            e.setApprovalId(approvalId);
            repository.save(e);
        });
    }

    /** 게이트 통과 전 실패(예: approval 거부)는 mutation이 없었으므로 예약을 해제한다. */
    @Transactional
    public void abandon(String key, UUID tenantId) {
        repository.findByIdemKeyAndTenantId(key, tenantId)
                .filter(e -> STATUS_PROCESSING.equals(e.getStatus()))
                .ifPresent(repository::delete);
    }

    public enum CheckKind { NEW, DUPLICATE, PROCESSING, CONFLICT }

    public record CheckResult(
            CheckKind kind,
            String result,
            String responseStatus,
            Integer httpStatus,
            UUID approvalId
    ) {
        public static CheckResult created() {
            return new CheckResult(CheckKind.NEW, null, null, null, null);
        }

        public static CheckResult duplicate(String result, String responseStatus, Integer httpStatus) {
            return duplicate(result, responseStatus, httpStatus, null);
        }

        public static CheckResult duplicate(String result, String responseStatus, Integer httpStatus, UUID approvalId) {
            return new CheckResult(CheckKind.DUPLICATE, result, responseStatus, httpStatus, approvalId);
        }

        public static CheckResult processing() {
            return new CheckResult(CheckKind.PROCESSING, null, null, null, null);
        }

        public static CheckResult conflict() {
            return new CheckResult(CheckKind.CONFLICT, null, null, null, null);
        }
    }
}
