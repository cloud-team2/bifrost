package com.bifrost.ops.provisioning.port;

import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.dto.TenantProvisionResponse;

/**
 * 워크스페이스 K8s 리소스 프로비저닝 포트.
 * auth/workspace 영역은 이 포트만 의존하며, 실제 구현은 provisioning.impl 하위에 둔다.
 */
public interface TenantProvisionerPort {

    TenantProvisionResponse provision(TenantProvisionRequest req);
}
