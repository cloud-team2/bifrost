package com.bifrost.ops.secret;

/**
 * DB 자격증명을 외부 Secret 저장소에 보관하고 참조(secret_ref)로만 다루는 추상화.
 *
 * <p>설계 §3(Database Registry) Step 2:
 * <pre>
 *   put(credential)    -> secret_ref   // 등록 시 1회
 *   resolve(secret_ref) -> credential   // provisioning 시점에만 호출
 * </pre>
 *
 * <p>핵심 원칙:
 * <ul>
 *   <li>메타DB(`datasources`)에는 {@code secret_ref}만 저장한다. 평문·암호문 저장 금지.</li>
 *   <li>{@code resolve}는 Connector CR 생성 같은 provisioning 시점에만 호출한다
 *       (예: {@code KafkaConnectorProbe}/real provisioner #12). 결과는 로그·State에 남기지 않는다.</li>
 *   <li>MVP는 in-memory mock({@code InMemorySecretStore}). 인터페이스를 그대로 두고
 *       이후 K8s Secret / Secrets Manager 구현으로 교체한다.</li>
 * </ul>
 *
 * <p>{@code put}은 설계의 {@code put(credential)}에 {@link SecretContext}를 더한다.
 * secret_ref가 DNS-safe한 K8s Secret 이름으로 쓰일 수 있어야 하므로 식별 정보가 필요하다
 * ({@link SecretRefNaming}).
 */
public interface SecretStore {

    /**
     * 자격증명을 저장하고 참조를 반환한다. datasource 등록 시 1회 호출한다.
     *
     * @return 메타DB에 저장할 secret_ref
     */
    String put(SecretContext context, DbCredential credential);

    /**
     * secret_ref로 자격증명을 해석한다. provisioning 시점에만 호출한다.
     *
     * @throws SecretStoreException ref에 해당하는 자격증명이 없으면
     */
    DbCredential resolve(String secretRef);

    /**
     * secret_ref가 가리키는 자격증명을 삭제한다(datasource 삭제·로테이션 정리용).
     * 없는 ref에 대한 호출은 무시한다(idempotent).
     */
    void delete(String secretRef);
}
