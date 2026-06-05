package com.bifrost.ops.secret.mock;

import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.SecretRefNaming;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.secret.SecretStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVP용 in-memory {@link SecretStore} 구현.
 *
 * <p>자격증명을 프로세스 메모리(map)에 보관한다. 재시작 시 사라지므로 운영용이 아니라
 * mock-first E2E(등록→provisioning 흐름)를 먼저 잇기 위한 구현이다. 이후 K8s Secret /
 * Secrets Manager 구현으로 교체한다(인터페이스 동일).
 *
 * <p>활성 조건: {@code secret-store.provider=mock} (미설정 시 기본값). 로그에는 secret_ref만
 * 남기고 user/password는 절대 남기지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "secret-store", name = "provider", havingValue = "mock")
public class InMemorySecretStore implements SecretStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySecretStore.class);

    private final Map<String, DbCredential> store = new ConcurrentHashMap<>();

    public InMemorySecretStore() {
        log.warn("InMemorySecretStore 활성화 — 자격증명을 메모리에만 보관합니다(MVP/mock 전용, 운영 금지).");
    }

    @Override
    public String put(SecretContext context, DbCredential credential) {
        String secretRef = SecretRefNaming.build(context);
        store.put(secretRef, credential);
        log.debug("secret stored: ref={}", secretRef); // 값은 로깅하지 않음
        return secretRef;
    }

    @Override
    public DbCredential resolve(String secretRef) {
        DbCredential credential = store.get(secretRef);
        if (credential == null) {
            throw SecretStoreException.notFound(secretRef);
        }
        return credential;
    }

    @Override
    public void delete(String secretRef) {
        store.remove(secretRef);
        log.debug("secret deleted: ref={}", secretRef);
    }
}
