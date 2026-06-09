package com.bifrost.ops.governance.evidence;

import com.bifrost.ops.governance.evidence.persistence.entity.EvidenceRefEntity;
import com.bifrost.ops.governance.evidence.persistence.repository.EvidenceRefRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** mutation 전후 스냅샷을 evidence_ref 테이블에 저장한다(S3). */
@Service
public class EvidenceStore {

    private final EvidenceRefRepository repository;
    private final ObjectMapper objectMapper;

    public EvidenceStore(EvidenceRefRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID record(UUID tenantId, UUID mutationId, String stage, Map<String, Object> snapshot) {
        EvidenceRefEntity entity = new EvidenceRefEntity();
        entity.setTenantId(tenantId);
        entity.setMutationId(mutationId);
        entity.setStage(stage);
        entity.setSnapshot(toJson(snapshot));
        return repository.save(entity).getId();
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
