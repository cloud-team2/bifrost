package com.bifrost.ops.secret;

import java.util.UUID;

/**
 * 자격증명을 저장할 때 secret_ref 이름을 만들기 위한 식별 컨텍스트.
 *
 * <p>설계의 {@code put(credential)} 시그니처에 컨텍스트를 더한 이유:
 * secret_ref는 K8s Secret 이름으로 그대로 쓰일 수 있어야 하므로(추후 K8s 구현),
 * 어떤 테넌트의 어떤 datasource 자격증명인지 알아야 DNS-safe하고 추적 가능한
 * 이름을 만들 수 있다. {@link SecretRefNaming} 참조.
 *
 * @param tenantId       소유 테넌트(=workspace) id
 * @param datasourceName datasource 표시 이름(슬러그로 변환되어 ref에 포함)
 */
public record SecretContext(UUID tenantId, String datasourceName) {

    public SecretContext {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (datasourceName == null || datasourceName.isBlank()) {
            throw new IllegalArgumentException("datasourceName must not be blank");
        }
    }
}
