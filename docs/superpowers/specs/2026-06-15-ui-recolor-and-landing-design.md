# UI 리컬러 & 랜딩 페이지 디자인 스펙

- 작성일: 2026-06-15
- 대상: bifrost 프론트엔드 (`services/frontend`)
- 상태: 구현 대기 (팀원 핸드오프용)

### 참고 자산 (이 스펙과 함께 커밋)
- 모노 로고: [`assets/logo-mono.svg`](assets/logo-mono.svg) — 라이트 배경용 `#0D0D0D` 단색. 다크 배경엔 `fill`을 `#FFFFFF`로 교체. `services/frontend/public/logo.svg` 교체본 + `BrandMark.tsx` fallback 단색화의 기준.
- 랜딩 시안: [`assets/landing-example.html`](assets/landing-example.html) — 브라우저로 열면 실제 톤·섹션·three.js 배경 동작 확인 가능. (정적 시안이며 그대로 이식하지 말고, 실제 컴포넌트로 재구현.)

## 1. 목표

현재 프론트엔드의 레이아웃은 유지하되, **색감만 바꿔** "Claude가 한 시간에 만든 서비스" 인상을 없애고, Airbyte·Hevo·Fivetran 같은 매력적인 SaaS 톤으로 끌어올린다. 더불어 배포 주소 접속 시 로그인 화면이 곧장 나오는 대신 **제품 소개 랜딩 페이지**를 먼저 노출하고, 거기서 로그인/데모 요청으로 진입하게 한다.

핵심 방향은 **"Codex 모노크롬"** — OpenAI Codex 랜딩을 벤치마크한 흰 배경 + 검정 잉크 + 단일 오류색 체계.

## 2. 디자인 원칙 (왜 이렇게 가는가)

"AI 티"는 채도 문제만이 아니다. 다음을 모두 지킨다.

1. **기본 프레임워크 팔레트 탈피** — Tailwind 기본 indigo/rose/emerald를 그대로 쓰지 않는다.
2. **검정이 곧 포인트** — 별도 브랜드 컬러를 두지 않는다. 버튼·로고·활성 상태는 검정/중립 회색.
3. **유채색은 오류 하나** — 화면에서 색이 들어가는 곳은 오류(인시던트/실패)뿐. 정상·실행중·지연은 무채색. 그래야 문제가 생겼을 때 즉시 눈에 들어온다. (정상까지 색칠하면 빨강이 초록과 경쟁해 인지가 느려진다.)
4. **라이트 전용** — 다크 모드는 이번 범위에서 제외(후속 과제). 토큰을 의미 기반으로 정리해 나중에 붙이기 쉽게 한다.

## 3. 범위

| 영역 | 변경 | 비고 |
|---|---|---|
| 구현된 콘솔 화면 전체 | **색상 토큰만 교체** | 레이아웃·DOM 구조·간격·아이콘·문구 불변 |
| 사이드바 | 다크 → 화이트 | 색 반전에 따른 텍스트 대비 클래스 교체 + 우측 보더 1줄 (구조 불변) |
| 로고 | 무지개 그라디언트 → 모노 잉크 단색 | 글리프(다리 형태) 동일, 색만 |
| 랜딩 페이지 | **신규 추가** | `/` 진입 흐름 변경 포함 |

명시적 제외(후속): 다크 모드, 실제 고객 성과 수치, 외부 문서 링크, 데모/문의 폼 백엔드.

## 4. 디자인 시스템 (팔레트)

색 토큰의 단일 출처는 `services/frontend/src/styles/index.css`의 `@theme` 블록이다. 단, 일부 색이 `Charts.tsx`·`blocks.tsx`·각 페이지에 하드코딩되어 있어 함께 손봐야 한다(§5 참고).

### 4.1 중립 / 표면

| 용도 | 현재 | → 신규 |
|---|---|---|
| 배경(backdrop) | `#f4f5f7` | `#FAFAFA` |
| 표면(카드/surface) | `#ffffff` | `#FFFFFF` |
| 보더 | gray-200 등 | `#ECECEC` |
| 본문 텍스트(ink) | `#29313d` / `--color-ink #1f2937` | `#0D0D0D` |
| 보조 텍스트(muted) | gray-500/600 | `#6B6B73` |
| 옅은 텍스트 | gray-400 | `#9A9A9A` |
| 스크롤바 thumb | `#d4d7dd` | `#ECECEC` |

### 4.2 포인트(brand) = 검정·중립 — `--color-brand-*` 재정의

브랜드 스케일을 "그레이스케일 + 잉크"로 전면 교체한다. 클래스명(`brand-600` 등)은 그대로 두고 토큰 값만 바꾼다 → 마크업 변경 없이 대부분 반영.

| 토큰 | 현재 | → 신규 | 쓰임 |
|---|---|---|---|
| `--color-brand-50` | `#eef1fe` | `#EDEDED` | 활성 메뉴/배지 틴트(중립 회색 채움) |
| `--color-brand-100` | `#dfe4fd` | `#E3E3E3` | |
| `--color-brand-300` | `#9aa8f6` | `#BDBDBD` | disabled 버튼 |
| `--color-brand-500` | `#4a5fe6` | `#2A2A2A` | |
| `--color-brand-600` | `#3b53e0` | `#0D0D0D` | primary 버튼/활성 텍스트 |
| `--color-brand-700` | `#2f43bd` | `#000000` | 버튼 hover |
| `--color-brand-800` | `#283898` | `#000000` | |

활성 메뉴/탭 등 "brand 텍스트"로 쓰이던 곳은 `#0D0D0D`(잉크)로 떨어진다. (별도 채도 색 아님.)

### 4.3 상태색 — 오직 오류만 유채색

| 상태 | 현재 계열 | → 신규 | 표현 |
|---|---|---|---|
| **오류 / 실패 / 인시던트** | rose | **`#C0392B`** | 헤더·사이드바 카운트는 **솔리드**(흰 글자), 목록 실패 행은 텍스트 `#C0392B` + 행 옅은 빨강 배경 `#FCF3F2` |
| 정상 / ACTIVE | emerald | **무채색** | 점·색 제거. 라벨만 `#8A8A8A` |
| 실행중 / running | brand(indigo) | **무채색** | `#8A8A8A` |
| 지연 / 경고 | amber | **무채색**(기본) | `#6B6B73`, 수치만 `#0D0D0D` 강조. (선택: 옅은 호박색 옵션 가능하나 기본은 중립) |
| 정보(info) | sky | 무채색 | `#6B6B73` |

> 결정: 정상/실행중/지연에서 색·점을 모두 제거한다. `blocks.tsx`의 `StatusBadge` TONE 매핑이 단일 출처.

### 4.4 차트 (`Charts.tsx`)

| 요소 | 현재 | → 신규 |
|---|---|---|
| `CHART_COLORS.brand` (1차 시리즈) | `#4a5ad0` | `#0D0D0D` |
| 보조 시리즈 2·3차 | emerald/violet 등 | `#8A8A8A`, `#C8C8C8` (그레이 스케일) |
| `CHART_COLORS.red` | `#e05c5c` | `#C0392B` (오류 시리즈) |
| 축 텍스트 | `#94a3b8` | `#9A9A9A` |
| CartesianGrid | `#eef0f3` | `#F1F1F1` |
| Tooltip border | `#e5e7eb` | `#ECECEC` |
| ReferenceLine | `#ef4444` | `#C0392B` |

emerald/amber/violet 시리즈는 그레이 스케일 또는 오류색(빨강)으로만 매핑한다(레인보우 제거).

### 4.5 버튼 (`ui.tsx`)

- **primary**: 검정 `#0D0D0D` / hover `#000` / 글자 흰색. (brand 토큰 재정의로 자동)
- **secondary**: 흰 배경 + 보더 `#D9D9D9` + 텍스트 `#0D0D0D`
- **danger**: 흰 배경 + 보더/텍스트 `#C0392B`
- **dark**(랜딩용 검정 pill): 그대로 유지
- Alerts의 `Run` 버튼(`bg-gray-900`): 콘솔 primary가 이미 검정이므로 그대로 검정 유지(일관). 추가 변경 불필요.

### 4.6 타이포그래피

폰트(Inter / JetBrains Mono)는 변경하지 않는다. 단, 메트릭·수치에는 `font-variant-numeric: tabular-nums` 적용(이미 일부 적용됨, 누락분 보강).

## 5. 콘솔 리컬러 — 파일별 변경 목록

> 원칙: 대부분 `@theme` 토큰 값 + 하드코딩 hex 교체. 마크업/클래스 구조는 불변. **레이아웃을 바꾸지 않는다.**

### 5.1 `src/styles/index.css` — 단일 핵심 지점
- `@theme`의 `--color-brand-*` 전면 교체 (§4.2)
- `--color-ink` → `#0D0D0D`, `--color-rail` → `#FFFFFF`(+소비처에 우측 보더), `--color-rail-hover` → `#EDEDED`
- `body` background `#f4f5f7` → `#FAFAFA`, color `#29313d` → `#0D0D0D`
- `.scroll-thin` thumb `#d4d7dd` → `#ECECEC`
- (권장) 의미 토큰 신설: `--color-surface #FFFFFF`, `--color-border #ECECEC`, `--color-muted #6B6B73`, `--color-status-error #C0392B`, `--color-neutral-fill #EDEDED`. 후속 다크 모드 대비.

### 5.2 `src/components/Charts.tsx`
- `CHART_COLORS` 및 축/그리드/툴팁/refLine 하드코딩 hex 교체 (§4.4)

### 5.3 `src/components/ui.tsx`
- Button variant·IconButton·Badge·Switch·Checkbox·Spinner·TextField focus·Tabs는 brand 토큰 재정의로 자동 반영
- **클래스 교체 필요(보라/하늘 잔재 제거)**: `LoadingTile`·`EmptyState`의 `bg-violet-100/text-violet-500` → 중립(`#EDEDED`/`#6B6B73`), `Avatar`의 `from-sky-400 to-indigo-500` 그라디언트 → 단색 잉크 `#0D0D0D`
- `Badge.green/amber/purple` → 상태 원칙에 맞게 중립 또는 제거, `Badge.rose` → `#C0392B`

### 5.4 `src/components/blocks.tsx` (StatusBadge / TONE — 상태 단일 출처)
- TONE 매핑 교체: 오류=`#C0392B`(솔리드/텍스트), 그 외(정상·실행중·지연·info)= 무채색(점·배경 제거, 라벨만 `#8A8A8A`/`#6B6B73`)

### 5.5 셸 — `shell/Sidebar.tsx`, `Header.tsx`, `StatusBar.tsx`, `ConsoleShell.tsx`
- **사이드바 다크→화이트 (예외: 텍스트 대비 클래스 교체 필요, 구조 불변)**:
  - 컨테이너 `bg-rail` → 화이트 + `border-r border-[#ECECEC]`
  - `text-white`/`text-gray-300/400`/`border-white/8|10` → ink/muted(`#0D0D0D`/`#6B6B73`)/light border
  - 활성 메뉴 `bg-brand-600 text-white`(솔리드) → `bg-[#EDEDED] text-[#0D0D0D]`(중립 채움)
  - 사이드바 인시던트 카운트 → 솔리드 `#C0392B`
  - 연결 dot(emerald) → 중립 `#8A8A8A`
- Header: 인시던트 배지 → 솔리드 `#C0392B`, breadcrumb/Sign out → ink/muted
- StatusBar: pulse dot(emerald) → 중립, 배경/보더 토큰화
- ConsoleShell `main` `bg-zinc-50` → `#FAFAFA`; AI 드로어 zap 박스 그라디언트(`from-brand-500 to-violet-600`) → 단색 잉크. **폭·애니메이션 등 레이아웃 불변.**

### 5.6 인증 — `pages/Login.tsx`, `Register.tsx`, `components/BrandMark.tsx`
- 그라디언트 배경 끝색 `to-brand-50` → `#EDEDED`(또는 제거), 카드/보더/텍스트 토큰 정렬
- primary 버튼·input focus → brand 토큰(검정)으로 자동
- 데모 계정 아바타·배지 sky/violet → 중립 잉크/회색
- BrandMark → §6 로고 교체

### 5.7 페이지 — `pages/dev/Pipelines.tsx`, `PipelineDetail.tsx`, `Alerts.tsx`
- 대부분 brand·TONE·CHART_COLORS 재정의로 자동. 인라인 상태색(`border-rose-400`, `bg-emerald-400` 등)은 상태 원칙대로 교체(오류=빨강, 그 외 무채색)
- **예외**: `PipelineDetail`의 코드블록 `bg-[#1b1e24]` 2곳은 사이드바 rail과 hex만 같을 뿐 용도가 다름 → 사이드바 화이트 전환과 분리해 다크 코드블록 유지(예: `bg-[#18181B]`)
- Alerts `Run` 버튼은 검정 유지(§4.5)

## 6. 로고

- 현재: `public/logo.svg` (무지개 그라디언트) + `BrandMark.tsx` fallback 그라디언트
- 변경: **모노 잉크 단색 실루엣**. 글리프(무지개 다리 형태)는 동일, 8개 그라디언트 제거하고 모든 path를 단일색으로.
  - 라이트 배경: `#0D0D0D`
  - 다크 섹션(랜딩 작동방식·배포·CTA): 흰색 `#FFFFFF` (동일 글리프 색 반전)
- 구현: `public/logo.svg`를 `fill="currentColor"` 단색 버전으로 교체하고, `BrandMark.tsx`는 `color`로 컨텍스트별 흑/백 제어. fallback 인라인 SVG도 동일 단색화.
- (옵션) 원본 무지개는 파비콘/특수 모먼트용으로 별도 보관 가능. 기본 파비콘도 모노로 교체 권장.

## 7. 랜딩 페이지 (신규)

### 7.1 진입 흐름
- 현재: 비로그인 시 `/`가 곧장 로그인. 라우팅은 react-router가 아니라 `store/AppStore` 상태 기반(View enum + authView).
- 변경: **비로그인 + 미진입 상태의 최상위 화면으로 `landing` 추가**. 흐름:
  - `/` → (비로그인) 랜딩 → [로그인] 또는 [데모 요청/도입 문의]
  - [로그인] 클릭 → 기존 `authView='login'` 화면 → 로그인 성공 → 프로젝트 목록 → 콘솔
  - 이미 로그인된 사용자가 접속 → 랜딩 건너뛰고 콘솔/프로젝트 목록로 직행(기본값)
- 구현: `App.tsx`의 분기에 `app.currentUser`가 없고 `authView`가 미설정일 때 `Landing`을 렌더. 로그인/회원가입 버튼이 `authView`를 설정. (AppStore에 `authView` 초기값을 `null`(랜딩)으로 두고 버튼에서 `'login'|'register'` 설정하는 방식 권장.)

### 7.2 톤 & 구조 (Codex 벤치마크, 흰/검 교차)
- 흰 배경 기조, **작동 방식·배포 섹션만 검정 배경**(흰/검 리듬). 최종 CTA·푸터도 검정 앵커.
- 검정 섹션 텍스트 밝기: 제목 `#FFFFFF`, 본문 `#CFCFCF`, eyebrow `#B0B0B0`, 카드 `#171717`/보더 `#2C2C2C`.
- 섹션 순서:
  1. **Nav** — 로고(모노) + `제품 · 배포 · 보안 · 로그인 · 데모 요청`. 제품/배포/보안은 각 섹션으로 스크롤(앵커), 로그인/데모 요청은 액션. (문서는 해당 섹션이 없어 제외.)
  2. **히어로** — eyebrow "AI 기반 분산 데이터 플랫폼", 헤드라인 "파이프라인은 클릭 몇 번으로, 장애는 AI 에이전트가 진단합니다", 서브 "복잡한 Kafka 설정 없이 EDA·CDC 파이프라인을 구축하고, 이상이 감지되면 AI가 근본 원인과 조치를 제안합니다 — 승인하면 실행(HITL).", CTA 검정 [데모 요청] + 아웃라인 [도입 문의 →]. **뒤에 three.js 배경(§7.3)**.
  3. **히어로 스크린샷** — 리컬러된 실제 콘솔(파이프라인) 화면.
  4. **3기능** — EDA `이벤트 스트림` / CDC `데이터 동기화` / AI·RCA `AI 장애 진단`. (= "제품" 앵커)
  5. **작동 방식**(검정) — [01] DB 연결(secretRef) → [02] 파이프라인 자동 생성(토픽·파티션·Debezium/JDBC) → [03] AI 감지·진단·조치(승인 후 실행).
  6. **노코드 / AI·HITL** — 좌우 교차 심화 2블록.
  7. **배포**(검정) — 자사 클라우드 / VPC / 온프레미스 / 폐쇄망(air-gapped). (= "배포" 앵커)
  8. **안전장치** — Policy Guard · HITL 승인 · 감사 로그 · secretRef · Evidence 기반 RCA. (= "보안" 앵커)
  9. **최종 CTA**(검정) — [데모 요청] + [도입 문의].
  10. **푸터**(검정).
- B2B 문법: **가격표 없음**. 1차 전환 = 데모 요청, 2차 = 도입 문의. 고객 성과 수치는 후속(현재 미포함).

### 7.3 three.js 배경
- 라이브러리: three.js (CDN 또는 의존성 추가). 히어로 영역 한정.
- 동작: 점(Points) 필드가 사인파로 잔잔히 물결(모노크롬, 점 색 `#a9a9a9` 수준, opacity ~0.78). 헤드라인 영역은 흰 베일(radial-gradient)로 덮어 **가독성 우선**.
- 성능/접근성: 히어로 보일 때만 렌더(IntersectionObserver), `prefers-reduced-motion`이면 정지(정적 점 필드). 모바일은 점 밀도 축소. WebGL 미지원 시 정적 배경으로 폴백.

### 7.4 카피 근거
모든 카피는 `docs/`(spec.md, scenario.md, design/*)와 실제 UI 라벨에 근거. 과장(고객 수치 등) 금지. AI는 "RCA 어시스턴트"(사전 정의 카탈로그 내 선택, 자유 생성 아님)임을 반영.

## 8. 반응형 / 접근성
- 랜딩: 데스크톱 우선, 태블릿/모바일에서 3열→1열, nav 햄버거(후속 가능).
- 명도 대비 WCAG AA 충족(본문/배경, 검정 섹션 포함). 오류색 `#C0392B`는 텍스트로 쓸 때 AA 확인.
- 상태를 색으로만 전달하지 않도록 라벨 텍스트 병기(정상/실패 등).

## 9. 검증
- `services/frontend` 빌드 통과.
- 로컬 풀스택(백엔드+프론트) 기동 후 **콘솔과 랜딩 두 화면을 실제로 띄워 육안 검증** 후 커밋.
- 회귀 확인: 레이아웃·간격·아이콘·문구가 기존과 동일한지(색만 바뀌었는지) 주요 페이지(Pipelines, PipelineDetail, Alerts, Login, Projects, Cluster, Settings) 스냅샷 비교.

## 10. 구현 순서 (팀원 핸드오프)
1. `index.css @theme` 토큰 교체 + 의미 토큰 신설 → 전역 반영 확인.
2. `Charts.tsx` / `blocks.tsx` / `ui.tsx` 하드코딩 색·상태 매핑 교체.
3. 셸(사이드바 다크→화이트 + 대비 클래스, 헤더/상태바) 교체. 코드블록 `#1b1e24` 분리.
4. 인증·페이지 잔여 인라인 색(violet/sky/상태) 정리.
5. 로고 모노 교체(public/logo.svg + BrandMark + 파비콘).
6. 랜딩 페이지 신규 구현(`pages/Landing.tsx` 등) + `App.tsx`/`AppStore` 진입 흐름 + three.js 배경.
7. 빌드·로컬 풀스택 검증 → 커밋/PR.

## 11. 미해결 / 후속 과제
- 다크 모드(토큰은 의미 기반으로 준비, 구현은 후속).
- 데모 요청/도입 문의 폼·엔드포인트.
- 외부 문서(`docs/`) 공개 링크 — 필요 시 nav에 외부 링크로 재추가.
- 실제 고객 성과 수치/로고월.
- 랜딩 모바일 nav(햄버거).
