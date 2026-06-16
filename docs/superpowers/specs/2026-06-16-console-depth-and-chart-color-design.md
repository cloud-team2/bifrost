# 콘솔 깊이·대비 보강 + 데이터 시각화 색상 1톤 (#719 후속)

- 작성일: 2026-06-16
- 대상: bifrost 프론트엔드 (`services/frontend`)
- 이슈: #762 / 선행: #719 (Codex 모노크롬 리컬러 + 랜딩)
- 상태: 구현 대기

## 1. 배경

#719로 콘솔을 "Codex 모노크롬"으로 리컬러한 뒤, 랜딩은 호평이지만 **콘솔이 평면적·칙칙**하다는 피드백. 라이브 콘솔(클러스터·파이프라인) 관찰 결과 원인은 색 부재가 아니라 **대비·깊이·앵커의 부재**다:

- 배경 `#FAFAFA` + 카드 `#FFFFFF` + 보더 `#ECECEC`가 명도 96~100% 한 띠에 몰려 전부 평평한 흰 벌판으로 녹음.
- 카드에 그림자(깊이)가 없어 레이어감 제로.
- 사이드바 다크→화이트 전환으로 콘솔의 시각적 척추(앵커)가 사라짐.
- 차트 보조선이 옅은 회색이라 데이터가 흐릿.

랜딩이 살아있는 이유(흰/검 대비·깊이)를 색을 되돌리지 않고 콘솔에 이식한다. 디자인 비교는 Visual Companion 목업으로 검토했고 **B안(화이트 사이드바 유지 + 깊이·대비 + 데이터 색)**으로 확정.

## 2. 원칙

1. **모노크롬 골격 유지** — 셸·텍스트·상태는 그대로 무채색.
2. **오류만 빨강(`#C0392B`)** — 상태/인시던트의 색 규칙(#719) 불변.
3. **데이터 시각화에만 색 1톤** — 차트·게이지·스파크라인·진행바에 잉크블루 `#3A47C2` 단일 톤 허용. **파랑 = 데이터, 빨강 = 문제**로 의미가 분리되어 충돌 없음.
4. **사이드바는 화이트 유지** (#719 §5.5 결정 존중). 활성 메뉴만 강한 앵커로.
5. **레이아웃·DOM·간격·문구 불변** — 색·그림자·토큰 값만 변경.

## 3. 디자인 시스템 변경

### 3.1 토큰 (`src/styles/index.css` @theme)
| 토큰 | 현재 | → 신규 | 용도 |
|---|---|---|---|
| `--color-data` | (없음) | `#3a47c2` | **신설** — 데이터 시각화 단일 색 |
| `--color-border` | `#ececec` | `#e4e4e7` | 보더 또렷 |
| body / 콘솔 배경 | `#fafafa` | `#f5f5f6` | 흰 표면 부유감(카드 분리) |

> 랜딩(`Landing.tsx`)은 자체 `#FAFAFA`를 사용하므로 영향 없음 — 콘솔 표면만 조정.

### 3.2 깊이 (그림자)
공용 표면 컴포넌트에 미묘한 그림자:
```
box-shadow: 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.04);
```
- `src/components/ui.tsx` `Card`
- `src/components/blocks.tsx` `MetricCard`, `Panel`

### 3.3 셸 (`src/components/shell/`)
- `Sidebar.tsx` 활성 메뉴: `bg-[#ededed] text-[#0d0d0d]` → **솔리드 잉크 pill** `bg-[#0d0d0d] text-white`. 사이드바 배경·구조는 화이트 그대로.
- `ConsoleShell.tsx` `main`: `bg-[#fafafa]` → `bg-[#f5f5f6]`.

### 3.4 데이터 색 = `#3a47c2`
- `src/components/Charts.tsx` `CHART_COLORS.brand`: `#0d0d0d` → `#3a47c2` (1차 시리즈). AreaChart는 `s.color` 기반 그래디언트라 자동으로 옅은 파란 면적 채움. 보조 시리즈(`emerald/amber/violet/slate`)는 그레이 유지, `red`(`#c0392b`) 유지. 축·그리드·툴팁 중립 유지.
- `src/components/ui.tsx` `Spark` 기본 `color`: `#0d0d0d` → `#3a47c2`. `ProgressBar` fill: `bg-[#0d0d0d]` → `bg-[#3a47c2]`.
- `src/components/blocks.tsx` `Gauge` 정상 fill: `#8a8a8a` → `#3a47c2`. 임계 초과(`>80%`)는 빨강 `#c0392b` 유지.

## 4. 유지 (변경 없음)
- `blocks.tsx` `StatusBadge`/`StatusDot` TONE — 상태색은 오류만 빨강(무채색 원칙) 그대로.
- `MetricCard` 수치 색 — 잉크(`#0d0d0d`) 유지. 오류 tone만 빨강. (파랑 아님)
- 로고 모노, 랜딩 페이지, 인증 화면.

## 5. 검증
- `services/frontend` `tsc` + `vite build` 통과.
- 단위 테스트(`vitest`) 통과.
- 로컬 풀스택 또는 라이브에서 콘솔 육안 검증 — 특히 클러스터(메트릭 카드·broker 카드·throughput 차트), 파이프라인 상세(차트·게이지), 인시던트.
- 회귀: 레이아웃·간격·문구가 #719 대비 동일한지(색·그림자만 변경) 확인.

## 6. 구현 순서
1. `index.css` 토큰(`--color-data`, border, bg) 교체.
2. 깊이: `ui.tsx Card`, `blocks.tsx MetricCard/Panel` 그림자.
3. 셸: `Sidebar` 활성 pill, `ConsoleShell` main 배경.
4. 데이터 색: `Charts.tsx`, `Spark`/`ProgressBar`, `Gauge`.
5. 빌드·테스트·육안 검증 → 커밋/PR.

## 7. 후속(범위 외)
- 다크 모드(토큰은 의미 기반 준비됨).
- 데이터 색의 추가 톤(틸/그린 등) 확장 — 필요 시 별도.
