---
doc_id: incident_report:2026-sink-auth-expired-002
doc_type: incident_report
title: Object storage sink access key revoked
tags: [SINK_AUTH_EXPIRED, rotate_credentials, object-storage]
source: synthetic
---

## 증상
events-archive-sink connector가 object storage 업로드 단계에서 403 AccessDenied를 반환했다. Topic consumption은 시도되었지만 파일 commit이 실패해 connector task가 재시도 루프에 들어갔다.

## 근거(evidence)
Storage audit trail에서 connector access key가 보안 정책에 의해 revoke된 기록이 있었다. 같은 bucket endpoint에 대한 network check는 성공했고 request timeout은 없었다.

## 근본원인
root_cause_id=SINK_AUTH_EXPIRED. Sink object storage credential이 revoke 또는 만료되어 쓰기 권한이 사라진 상황이다.

## 조치
`rotate_credentials` 도구로 storage credential owner에게 새 key 발급과 권한 scope 확인을 요청했다. 임시 조치로 `pause_connector`를 제안해 같은 batch의 반복 실패와 alert noise를 줄였다.

## 복구
새 key 적용 후 object upload가 성공했고 pending batch가 순차적으로 commit되었다. 이후 connector retry count와 403 응답 수가 정상 범위로 유지되었다.
