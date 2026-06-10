package com.bifrost.ops.governance.approval;

import com.bifrost.ops.governance.approval.persistence.entity.ApprovalEntity;
import com.bifrost.ops.governance.approval.persistence.repository.ApprovalRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 승인 토큰 유효성 검증 및 단일 사용 처리(S3). */
@Service
public class ApprovalValidator {

    private final ApprovalRepository approvalRepository;

    public ApprovalValidator(ApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    /**
     * 승인 토큰을 검증하고 사용 처리한다.
     * @throws ApiException 승인 없음·만료·params_hash 불일치·이미 사용
     */
    @Transactional
    public ApprovalEntity validateAndConsume(UUID approvalId, String paramsHash) {
        return validateAndConsume(approvalId, null, null, paramsHash);
    }

    /**
     * 승인 토큰을 tenant·operation·params scope까지 검증하고 사용 처리한다.
     * null tenantId/operation은 기존 최소 검증 호출부 호환을 위해 scope 검증을 생략한다.
     */
    @Transactional
    public ApprovalEntity validateAndConsume(UUID approvalId, UUID tenantId, String operation, String paramsHash) {
        ApprovalEntity approval = approvalRepository.findByIdForUpdate(approvalId)
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "approval not found: " + approvalId));

        if (!"APPROVED".equals(approval.getDecision())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval not in APPROVED state: " + approval.getDecision());
        }
        if (tenantId != null && !tenantId.equals(approval.getTenantId())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval tenant mismatch");
        }
        if (operation != null && !operation.equals(approval.getOperation())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval operation mismatch");
        }
        if (approval.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED, "approval expired");
        }
        if (approval.getUsedAt() != null) {
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_USED, "approval already used");
        }
        if (!paramsHash.equals(approval.getParamsHash())) {
            throw new ApiException(ErrorCode.APPROVAL_SCOPE_MISMATCH, "approval params_hash mismatch");
        }

        approval.setUsedAt(Instant.now());
        return approvalRepository.save(approval);
    }
}
