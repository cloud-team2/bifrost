package com.bifrost.ops.incident.dto;

/** 인시던트 사용자 상태 전이 요청(#558, 스펙 B.7). status = OPEN | INVESTIGATING | RESOLVED. */
public record IncidentStatusUpdateRequest(String status) {
}
