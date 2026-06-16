# 콘솔 UX 개선 — 사이드바 접기·상태배지·차트색·인시던트 모달 (#780)

- 작성일: 2026-06-16
- 대상: bifrost 프론트엔드 (`services/frontend`)
- 이슈: #780 / 선행: #770, #762, #719
- 상태: 구현 대기 (Visual Companion 목업으로 디자인 확정)

## 1. 변경 항목

### ① 좌측 사이드바 접기 + 한글 라벨
- 접기 토글 버튼 추가. **기본값 = 접힘**(아이콘만, 폭 축소). 접힘 시 라벨 숨김, 호버 `title` 툴팁.
- 라벨: `Pipeline → 파이프라인`, `Database → 데이터베이스` (클러스터·인시던트와 통일).
- 상태: `ConsoleShell`에 `sidebarCollapsed` state(기본 true) + `Sidebar`에 prop·토글 전달.

### ② AI 패널 기본 펼침
- `AppStore` `aiPanelOpen` 초기값 `false → true`.

### ③ 상태 배지 배경색(솔리드)
- `blocks.tsx` `StatusBadge`/`StatusDot` TONE을 **솔리드 배경 + 흰 글자**로:
  | 그룹 | 색 | 상태 |
  |---|---|---|
  | 정상 | `#157f4a` (초록) | healthy/active/RUNNING/STABLE/CONNECTED/resolved/ACTIVE |
  | 경고·지연 | `#d97316` (주황) | warning/lag/WARN/WARNING/REBALANCING/PARTIALLY_FAILED/investigating |
  | 대기·일시정지 | `#6b6b73` (회색) | paused/EMPTY/inactive/info/creating/UNASSIGNED |
  | 오류 | `#c0392b` (빨강) | error/DEAD/FAILED/critical/open/revoked |
- 동적 색이라 inline `style={{ background }}` + `text-white` 사용. StatusDot은 같은 색의 점.

### ④ 챗봇 인트로 문구 제거
- `BifrostAgent.tsx` `initialMessage` 제거(빈 입력창만).

### ⑤ Partition 2색
- `PipelineDetail.tsx` `PartitionViz`: 브로커별 색(brokerColors) 제거 → **정상=`#3a47c2`(파랑) / 편향(skew, msg>avg×1.3)=`#e0701f`(주황)** 만. Leader 분포 막대=파랑. "편향 감지" 배지=주황.

### ⑥ 차트 색
- **이벤트 타입 스택바**(`PipelineDetail` CDC op 분포): #719 이전 원본 복원 — **INSERT `#2ba27b` · UPDATE `#e3a52c` · DELETE `#e05c5c`**(커밋 f59e973). 범례·op 배지도 동일.
- **처리량(라인)**: 현행 유지(Produce 틸 `#0e9488` / Consume 파랑).
- **게이지/스파크라인**: 단색 파랑(현행 — Gauge 정상 파랑·>80% 빨강, Spark·ProgressBar 파랑).
- 모든 차트·스파크라인에 데이터 색이 빠짐없이 들어갔는지 점검(회색은 축·그리드만).

### ⑦ 인시던트 로그 → 모달
- `Alerts.tsx`: 우측 `xl:w-[380px]` 패널을 제거하고, `IncidentPanel`/`EventDetailPanel`을 **중앙 모달**(`Modal` 컴포넌트)로 렌더. 본문은 전체 폭. **Esc·바깥 클릭·X**로 닫힘(Modal이 제공). 패널 내부 디자인은 그대로.

## 2. 유지
- 셸·본문 텍스트 무채색 기조, 로고·랜딩, 레이아웃 골격(접기/모달 외).

## 3. 검증
- `tsc`+`vite build`+`vitest` 통과.
- 로컬/라이브 콘솔 육안: 사이드바 접힘·토글, AI 패널 펼침, 상태 배지 색, partition 2색, 이벤트 타입 원본색, 인시던트 모달(Esc/바깥/X).
