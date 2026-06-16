---
doc_id: incident_report:2026-source-network-reachability-001
doc_type: incident_report
title: Source database subnet unreachable
tags: [SOURCE_NETWORK_REACHABILITY, network_diagnostics, source]
source: synthetic
---

## 증상
payments-source connector가 source DB에 연결하지 못해 snapshot과 streaming read가 모두 중단되었다. 여러 재시작 시도에도 task는 connection timeout으로 실패했다.

## 근거(evidence)
Network diagnostics에서 connector worker subnet에서 source DB endpoint로 TCP 연결이 timeout되었고 DNS resolution은 성공했다. Source auth error는 없고 같은 credential로 bastion 내부 점검은 성공했다.

## 근본원인
root_cause_id=SOURCE_NETWORK_REACHABILITY. Bifrost worker에서 source endpoint까지의 network path가 막혀 source read가 불가능한 상황이다.

## 조치
`network_diagnostics` 도구로 subnet, security group, route table evidence를 수집하고 platform/customer network owner에게 escalation했다. Connector 재시작은 network path 복구 전까지 보류했다.

## 복구
Firewall rule이 복구된 뒤 TCP check와 connector task start가 모두 성공했다. CDC lag는 backlog 처리 후 정상화되었고 incident timeline에 네트워크 변경 시각을 기록했다.
