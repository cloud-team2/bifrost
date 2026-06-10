package com.bifrost.ops.governance.idempotency;

import com.bifrost.ops.governance.idempotency.IdempotencyGuard.CheckKind;
import com.bifrost.ops.governance.idempotency.IdempotencyGuard.CheckResult;
import com.bifrost.ops.governance.idempotency.persistence.entity.IdempotencyKeyEntity;
import com.bifrost.ops.governance.idempotency.persistence.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyGuardTest {

    private final IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
    private final IdempotencyGuard guard = new IdempotencyGuard(repository);

    @Test
    void completePersistsGovernanceScopeForDuplicateReplay() {
        UUID tenantId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID changeTicketId = UUID.randomUUID();
        IdempotencyKeyEntity row = processingRow(tenantId);
        when(repository.findByIdemKeyAndTenantId("idem-1", tenantId)).thenReturn(Optional.of(row));

        guard.complete(
                "idem-1",
                tenantId,
                "{\"ok\":true}",
                IdempotencyGuard.RESPONSE_OK,
                200,
                approvalId,
                changeTicketId);
        CheckResult replay = guard.check("idem-1", tenantId, "reset_offsets", "hash");

        assertThat(replay.kind()).isEqualTo(CheckKind.DUPLICATE);
        assertThat(replay.approvalId()).isEqualTo(approvalId);
        assertThat(replay.changeTicketId()).isEqualTo(changeTicketId);
        verify(repository).save(row);
    }

    private static IdempotencyKeyEntity processingRow(UUID tenantId) {
        IdempotencyKeyEntity row = new IdempotencyKeyEntity();
        row.setIdemKey("idem-1");
        row.setTenantId(tenantId);
        row.setStatus(IdempotencyGuard.STATUS_PROCESSING);
        row.setOperation("reset_offsets");
        row.setParamsHash("hash");
        row.setExpiresAt(Instant.now().plusSeconds(3600));
        return row;
    }
}
