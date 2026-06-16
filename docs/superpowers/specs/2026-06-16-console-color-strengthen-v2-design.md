# 콘솔 색 강화 2차 — error 솔리드 + 차트 다색 + partition 블루스케일 (#762 후속)

- 작성일: 2026-06-16
- 대상: bifrost 프론트엔드 (`services/frontend`)
- 이슈: #770 / 선행: #762, #719
- 상태: 구현 대기

## 1. 배경
#762 배포 후 피드백: (1) 여전히 칙칙 — 차트가 1차 시리즈만 색, 데이터 적은 쪽에만 색이 가면 안 보임. (2) error 표시가 옅은 분홍 배경+빨강 글자라 바랜 "AI 티". (3) 파이프라인-토픽 탭 partition이 broker-0만 파랑·나머지 회색이라 자의적(원래 리더 브로커별 색인데 #719에서 회색으로 평탄화된 잔재) + 편향 파티션이 회색이라 안 보임.

Visual Companion 목업으로 검토·확정.

## 2. 원칙 (#719→#762에서 진화)
- **셸·상태 텍스트는 무채색** 유지.
- **데이터 시각화는 색 허용**(차트·파티션) — 단 절제된 톤(고채도 sky/indigo 등 "AI 티" 배제).
- **오류는 솔리드 빨강 `#C0392B`** — 상태 배지/인시던트 모두 동일(흰 글자).

## 3. 변경

### 3.1 error 솔리드 통일
옅은 분홍 배경+빨강 글자 → **솔리드 빨강+흰 글자**(`1 open incident` 버튼과 동일).
- `blocks.tsx` `StatusBadge` `ERROR` tone: `{ dot:'bg-[#c0392b]', text:'text-[#c0392b]', bg:'bg-[#fcf3f2]' }` → `{ dot:'', text:'text-white', bg:'bg-[#c0392b]' }`
- `Alerts.tsx` `LEVEL_BADGE.error`: `bg-[#fcf3f2] text-[#c0392b]` → `bg-[#c0392b] text-white`
- 페이지 인라인 **error 상태 배지/칩**(`bg-[#fcf3f2] text-[#c0392b]` 형태의 작은 pill) → 솔리드.
  - 예외(그대로): 큰 error **메시지 배너**(문장형 박스), **실패 행 옅은 배경 tint**, 폼 필드 error 텍스트 — 솔리드로 만들면 과함.
- 정상/지연/info 등 비오류 배지는 무채색 그대로.

### 3.2 차트 다색 (CHART_COLORS 그레이스케일 해제)
`Charts.tsx` `CHART_COLORS` — 보조 시리즈에 또렷한 데이터 색 부여:
| 키 | 현재 | → 신규 | 쓰임 |
|---|---|---|---|
| brand | #3a47c2 | #3a47c2 (유지) | 1차·Consume·records |
| emerald | #8a8a8a | **#0e9488** (틸) | INSERT·Produce(틸) |
| amber | #b0b0b0 | **#c98a1a** (앰버) | UPDATE |
| violet | #c8c8c8 | **#7c5cd6** (바이올렛) | source 지연 |
| red | #c0392b | #c0392b (유지) | DELETE·오류 |
| slate | #9a9a9a | #9a9a9a (유지) | 축 |
- `OperatorCluster.tsx` 클러스터 throughput: Produce `CHART_COLORS.amber` → `CHART_COLORS.emerald`(틸)로 변경(목업과 동일, Consume=파랑/Produce=틸 양쪽 채색).

### 3.3 PartitionViz 블루스케일 (`PipelineDetail.tsx`)
다색 brokerColors → **블루 2단계**:
- `brokerColors`: 리더 브로커(brokers[0]) = `#3a47c2`(찐), 나머지 전부 = `#d2d6e6`(옅은 파랑 1색).
- 편향(skew, `messages > avg*1.3`) 막대: `CHART_COLORS.amber`(회색) → `#c0392b`(빨강).
- "편향 감지" 배지: 무채색 → 솔리드 빨강(막대와 일치).
- Leader 분포 막대도 같은 brokerColors 공유 → 리더 찐파랑/나머지 옅은파랑 일관.

## 4. 유지
- 정상/실행중/지연 상태 무채색, 셸·텍스트 무채색, 로고·랜딩, 레이아웃·DOM·문구 불변.

## 5. 검증
- `tsc`+`vite build`+`vitest` 통과.
- 로컬/라이브 콘솔 육안: error 배지 솔리드, 차트 다색, 토픽 탭 partition 블루스케일+편향 빨강.
