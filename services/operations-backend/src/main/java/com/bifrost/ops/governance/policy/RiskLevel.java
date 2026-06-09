package com.bifrost.ops.governance.policy;

/** mutation 위험 등급. HIGH는 프로덕션 락 시 승인 필요. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
