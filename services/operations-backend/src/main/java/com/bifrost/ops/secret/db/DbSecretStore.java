package com.bifrost.ops.secret.db;

import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.SecretRefNaming;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.secret.SecretStoreException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 기반 {@link SecretStore} 구현. 자격증명을 metadb의 {@code secrets} 테이블에 영속한다.
 *
 * <p>재시작 후에도 자격증명이 유지되므로 DB 재등록 없이 서비스 일관성을 유지한다.
 * credential_json은 {@code {"user":"...","password":"..."}} 형태로 내부 metadb에만 저장되며
 * API 응답이나 로그에는 노출하지 않는다.
 *
 * <p>활성 조건: {@code secret-store.provider=db}.
 */
@Component
@ConditionalOnProperty(prefix = "secret-store", name = "provider", havingValue = "db")
public class DbSecretStore implements SecretStore {

    private static final Logger log = LoggerFactory.getLogger(DbSecretStore.class);

    private final SecretRepository repo;
    private final ObjectMapper objectMapper;

    public DbSecretStore(SecretRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String put(SecretContext context, DbCredential credential) {
        String secretRef = SecretRefNaming.build(context);
        SecretEntity entity = new SecretEntity();
        entity.setSecretRef(secretRef);
        entity.setCredentialJson(toJson(credential));
        repo.save(entity);
        log.debug("secret stored in DB: ref={}", secretRef);
        return secretRef;
    }

    @Override
    @Transactional(readOnly = true)
    public DbCredential resolve(String secretRef) {
        return repo.findById(secretRef)
                .map(e -> fromJson(e.getCredentialJson()))
                .orElseThrow(() -> SecretStoreException.notFound(secretRef));
    }

    @Override
    @Transactional
    public void delete(String secretRef) {
        repo.deleteById(secretRef);
        log.debug("secret deleted from DB: ref={}", secretRef);
    }

    private String toJson(DbCredential credential) {
        try {
            return objectMapper.writeValueAsString(
                    new CredentialJson(credential.user(), credential.password()));
        } catch (JsonProcessingException e) {
            throw new SecretStoreException("자격증명 직렬화 실패", e);
        }
    }

    private DbCredential fromJson(String json) {
        try {
            CredentialJson c = objectMapper.readValue(json, CredentialJson.class);
            return new DbCredential(c.user(), c.password());
        } catch (JsonProcessingException e) {
            throw new SecretStoreException("자격증명 역직렬화 실패", e);
        }
    }

    record CredentialJson(String user, String password) {}
}
