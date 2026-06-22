import { useRef, type ReactNode } from 'react'
import { BrandMark } from '../components/BrandMark'
import { HeroBackground } from '../components/HeroBackground'

/**
 * 제품 소개 랜딩 페이지 (신규 #719, 디자인 통일 #879).
 *
 * 히어로는 "딥스페이스 + Bifröst 빔"(three.js, HeroBackground) — 워드마크를
 * Space Grotesk 로 키워 프로젝트명을 전면에 세운다. 하단 섹션은 흰/검 교차의
 * 모노크롬 기조를 유지하고, 유채색은 Bifröst 스펙트럼(브랜드 시그니처)과
 * 오류(#C0392B)에만 쓴다. 색은 콘솔 리컬러와 무관하게 명시 hex 로 고정.
 *
 * 진입 흐름: 비로그인 + 미진입 시 App 이 이 화면을 렌더. 모든 CTA(로그인·데모
 * 요청·도입 문의)는 `onEnter` 로 로그인 화면(데모 계정 포함)으로 보낸다. 데모/
 * 문의 폼 백엔드는 이번 범위에서 제외(스펙 §3).
 */
export function Landing({ onEnter }: { onEnter: () => void }) {
  const scrollTo = (id: string) => () =>
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })

  // 히어로 빔을 워드마크("o") 수직 중앙에 맞추기 위한 참조 (#879)
  const wordmarkRef = useRef<HTMLHeadingElement>(null)

  return (
    <div className="min-h-screen bg-[#FAFAFA] font-sans text-[#0D0D0D]">
      {/* ───────────────────────── HERO (딥스페이스 + Bifröst 빔, #879) — nav 포함 */}
      <section className="relative overflow-hidden bg-[#06070e]">
        {/* 성운(좌측) */}
        <div
          className="pointer-events-none absolute inset-0 z-0"
          style={{
            background:
              'radial-gradient(closest-side at 8% 62%, rgba(70,150,180,.40), rgba(70,150,180,0) 70%), radial-gradient(closest-side at 0% 48%, rgba(180,120,255,.20), rgba(180,120,255,0) 65%), linear-gradient(180deg,#070812,#05060c 60%,#040509)',
          }}
        />
        <HeroBackground className="absolute inset-0 z-[1] block h-full w-full" beamTargetRef={wordmarkRef} />
        {/* 비네트 — 가장자리를 눌러 텍스트 가독성 확보 */}
        <div
          className="pointer-events-none absolute inset-0 z-[2]"
          style={{
            background:
              'radial-gradient(ellipse 120% 90% at 50% 50%, rgba(0,0,0,0) 52%, rgba(0,0,0,.5))',
          }}
        />
        {/* NAV — 투명 오버레이. 히어로의 딥스페이스 배경(별·빔)이 그대로 비친다 (#879) */}
        <nav className="relative z-[5] mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2">
            <BrandMark size={24} />
            <span className="font-display text-[16px] font-bold lowercase tracking-tight text-white">bifrost</span>
          </div>
          <div className="flex items-center gap-1 text-[13px] text-[#c7c9d6]">
            <button onClick={scrollTo('product')} className="hidden rounded-md px-3 py-1.5 transition-colors hover:text-white sm:block">
              제품
            </button>
            <button onClick={scrollTo('deploy')} className="hidden rounded-md px-3 py-1.5 transition-colors hover:text-white sm:block">
              배포
            </button>
            <button onClick={scrollTo('security')} className="hidden rounded-md px-3 py-1.5 transition-colors hover:text-white sm:block">
              보안
            </button>
            <button onClick={onEnter} className="rounded-lg bg-white px-4 py-2 font-semibold text-[#0b0b0b] transition-colors hover:bg-[#ececec]">
              로그인
            </button>
          </div>
        </nav>
        <div className="relative z-[4] mx-auto max-w-4xl px-6 pt-8 pb-8 text-center sm:pt-10">
          <div className="font-display text-[11px] uppercase tracking-[0.2em] text-[#9aa0b8]">
            AI 기반 분산 데이터 플랫폼
          </div>
          <h1
            ref={wordmarkRef}
            className="bifrost-wm-glow font-display mt-3 font-bold lowercase tracking-[-0.045em] text-white"
            style={{ fontSize: 'clamp(64px,12vw,150px)', lineHeight: 0.9 }}
          >
            bifrost
          </h1>
          <p className="mx-auto mt-5 max-w-2xl text-[17px] font-semibold leading-snug tracking-tight text-[#e6e8f2] sm:text-[20px]">
            파이프라인은 클릭 몇 번으로, 장애는 AI 에이전트가 진단합니다
          </p>
          <p className="mx-auto mt-4 max-w-2xl text-[14px] leading-relaxed text-[#a7abbd]">
            복잡한 Kafka 설정 없이 EDA·CDC 파이프라인을 구축하고,
            <br />
            이상이 감지되면 AI가 근본 원인과 조치를 제안합니다 — 승인하면 실행(HITL).
          </p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <button onClick={onEnter} className="rounded-lg bg-white px-6 py-3 text-sm font-semibold text-[#0b0b0b] transition-colors hover:bg-[#ececec]">
              로그인
            </button>
            <button onClick={onEnter} className="rounded-lg border border-[#3a3d4d] bg-transparent px-5 py-3 text-sm font-semibold text-white transition-colors hover:bg-white/5">
              도입 문의 →
            </button>
          </div>
        </div>

        {/* 히어로 스크린샷 — 리컬러된 콘솔(파이프라인). 오류만 색. */}
        <div className="relative z-[4] mx-auto max-w-4xl px-6 pb-16">
          <ConsoleShot />
        </div>
      </section>

      {/* ───────────────────────── 3 FEATURES (= 제품 앵커) */}
      <section id="product" className="border-t border-[#EFEFEF] bg-[#FAFAFA]">
        <div className="mx-auto max-w-6xl px-6 py-16">
          <div className="grid gap-5 sm:grid-cols-3">
            <Feature
              kicker="EDA"
              title="이벤트 스트림"
              desc="변경을 감지해 Kafka 토픽으로 자동 스트리밍. 커넥터는 마법사가 프로비저닝합니다."
            />
            <Feature
              kicker="CDC"
              title="데이터 동기화"
              desc="Source→Sink 실시간 복제, 동기화율·지연을 한 화면에서 추적합니다."
            />
            <Feature
              kicker="AI · RCA"
              title="AI 장애 진단"
              desc="이상 시 근거 기반으로 원인·영향·조치를 진단, 승인 후 실행합니다."
            />
          </div>
        </div>
      </section>

      {/* ───────────────────────── 작동 방식 (dark) */}
      <section className="bg-[#0D0D0D] text-[#F2F2F2]">
        <div className="mx-auto max-w-6xl px-6 py-20">
          <div className="text-center">
            <div className="font-mono text-[11px] uppercase tracking-[0.16em] text-white">작동 방식</div>
            <h2 className="mt-3 text-[26px] font-semibold tracking-tight text-white sm:text-[30px]">
              DB 연결에서 AI 장애 대응까지
            </h2>
          </div>
          <div className="mt-12 grid gap-10 sm:grid-cols-3">
            <Step
              n="[01]"
              title="DB를 연결합니다"
              desc={
                <>
                  Source·Sink 접속 정보만 입력. 자격증명은 평문 저장 없이{' '}
                  <code className="rounded bg-white/15 px-1.5 py-0.5 font-mono text-[12px] text-white">secretRef</code> 로
                  보호됩니다.
                </>
              }
            />
            <Step
              n="[02]"
              title="파이프라인이 자동 생성됩니다"
              desc="테이블만 고르면 토픽·파티션·커넥터(Debezium/JDBC)를 자동 프로비저닝합니다."
            />
            <Step
              n="[03]"
              title="AI가 감지·진단·조치합니다"
              desc="임계값 초과 시 자동 인시던트 → 근본 원인 진단 → 권장 조치, 승인 후 실행합니다."
            />
          </div>
        </div>
      </section>

      {/* ───────────────────────── 노코드 / AI·HITL 교차 블록 */}
      <section className="bg-[#FAFAFA]">
        <div className="mx-auto max-w-5xl space-y-14 px-6 py-20">
          <FeatureRow
            eyebrow="No-Code"
            title="Kafka 전문 지식 없이도"
            desc="토픽 이름·파티션·오프셋·Connect 설정은 전부 자동 처리되고 숨겨집니다. 비기술 운영자도 파이프라인을 만듭니다."
            visual={
              <div className="flex items-center gap-2.5 font-mono text-[11px] text-[#6B6B73]">
                <span className="rounded-md border border-[#E0E0E0] px-2.5 py-1.5">MySQL</span>
                <span className="h-px w-6 border-t border-dashed border-[#CFCFCF]" />
                <span className="rounded-md bg-[#0D0D0D] px-2.5 py-1.5 text-white">bifrost</span>
                <span className="h-px w-6 border-t border-dashed border-[#CFCFCF]" />
                <span className="rounded-md border border-[#E0E0E0] px-2.5 py-1.5">Sink</span>
              </div>
            }
          />
          <FeatureRow
            reverse
            eyebrow="AI · HITL"
            title="장애는 AI가, 결정은 사람이"
            desc="근거(evidence) 기반으로 원인·영향·권장 조치를 제시합니다. 자유 생성이 아니라 사전 정의된 원인 카탈로그 안에서만 — 승인(Run) 후에만 실행되고 전부 감사 기록됩니다."
            visual={
              <div className="flex w-full flex-col gap-2">
                <div className="rounded-md border border-[#EEEEEE] border-l-2 border-l-[#C0392B] bg-white px-3 py-2 text-[11px]">
                  <strong className="font-semibold">orders-cdc</strong> — sink 연결 실패
                </div>
                <div className="font-mono text-[10px] text-[#9A9A9A]">RCA · 근본 원인: connector DOWN</div>
                <div className="flex items-center justify-between text-[11px]">
                  <span>권장 조치: Connector 재시작</span>
                  <span className="rounded-md bg-[#0D0D0D] px-2.5 py-1 text-[10px] font-semibold text-white">Run</span>
                </div>
              </div>
            }
          />
        </div>
      </section>

      {/* ───────────────────────── 배포 (dark, = 배포 앵커) */}
      <section id="deploy" className="bg-[#0D0D0D] text-[#F2F2F2]">
        <div className="mx-auto max-w-6xl px-6 py-20">
          <div className="text-center">
            <div className="font-mono text-[11px] uppercase tracking-[0.16em] text-white">배포</div>
            <h2 className="mt-3 text-[26px] font-semibold tracking-tight text-white sm:text-[30px]">
              우리 인프라 안에서 운영합니다
            </h2>
            <p className="mx-auto mt-3 max-w-xl text-[14px] leading-relaxed text-white">
              데이터가 외부로 나가지 않도록, 폐쇄망·온프레미스까지 동일한 콘솔로.
            </p>
          </div>
          <div className="mt-12 grid gap-3 sm:grid-cols-4">
            <DeployCard title="자사 클라우드" sub="EKS 단일 클러스터" />
            <DeployCard title="VPC" sub="전용 네트워크 내" />
            <DeployCard title="온프레미스" sub="사내 데이터센터" />
            <DeployCard title="폐쇄망" sub="air-gapped" />
          </div>
        </div>
      </section>

      {/* ───────────────────────── 안전장치 (= 보안 앵커) */}
      <section id="security" className="bg-[#FAFAFA]">
        <div className="mx-auto max-w-4xl px-6 py-20 text-center">
          <h2 className="text-[22px] font-semibold tracking-tight sm:text-[24px]">안전장치를 기본값으로</h2>
          <div className="mt-7 flex flex-wrap justify-center gap-2.5">
            {[
              'Policy Guard · 정책 검증',
              'HITL 승인 실행',
              '전체 감사 로그(audit)',
              'secretRef 자격증명',
              'Evidence 기반 RCA',
            ].map((c) => (
              <span key={c} className="rounded-lg border border-[#E4E4E4] bg-white px-3.5 py-2 text-[12px] text-[#3A3A3A]">
                {c}
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* ───────────────────────── 최종 CTA (dark) */}
      <section className="bg-[#0D0D0D] text-center text-white">
        <div className="mx-auto max-w-3xl px-6 py-20">
          <h2 className="text-[26px] font-semibold tracking-tight sm:text-[30px]">도입을 검토 중이신가요?</h2>
          <p className="mt-3 text-[15px] text-white">팀 환경에 맞춘 데모와 배포 방식을 안내해 드립니다.</p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <button onClick={onEnter} className="rounded-lg bg-white px-6 py-3 text-sm font-semibold text-[#0D0D0D] transition-colors hover:bg-[#ECECEC]">
              로그인
            </button>
            <button onClick={onEnter} className="rounded-lg border border-[#3A3A3A] px-5 py-3 text-sm font-semibold text-white transition-colors hover:bg-white/5">
              도입 문의
            </button>
          </div>
        </div>
      </section>

      {/* ───────────────────────── 푸터 (dark) */}
      <footer className="border-t border-[#1F1F1F] bg-[#0D0D0D]">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-5 text-[12px] text-white">
          <div className="flex items-center gap-2">
            <BrandMark size={16} />
            <span>© 2026 bifrost</span>
          </div>
          <span>문서 · 보안 · 문의</span>
        </div>
      </footer>
    </div>
  )
}

/* ─────────────────────────────────────────── 서브 컴포넌트 */

function Feature({ kicker, title, desc }: { kicker: string; title: string; desc: string }) {
  return (
    <div className="rounded-xl border border-[#EEEEEE] bg-white p-5">
      <div className="font-mono text-[10px] uppercase tracking-[0.1em] text-[#B3B3B3]">{kicker}</div>
      <div className="mt-1.5 text-[15px] font-bold">{title}</div>
      <div className="mt-2 text-[13px] leading-relaxed text-[#6B6B73]">{desc}</div>
    </div>
  )
}

function Step({ n, title, desc }: { n: string; title: string; desc: ReactNode }) {
  return (
    <div>
      <div className="font-mono text-[11px] text-white">{n}</div>
      <div className="mt-1.5 text-[15px] font-bold text-white">{title}</div>
      <div className="mt-2 text-[13px] leading-relaxed text-white">{desc}</div>
    </div>
  )
}

function FeatureRow({
  eyebrow,
  title,
  desc,
  visual,
  reverse,
}: {
  eyebrow: string
  title: string
  desc: string
  visual: ReactNode
  reverse?: boolean
}) {
  return (
    <div className={`flex flex-col items-center gap-8 sm:flex-row ${reverse ? 'sm:flex-row-reverse' : ''}`}>
      <div className="flex-1">
        <div className="font-mono text-[11px] uppercase tracking-[0.16em] text-[#A0A0A0]">{eyebrow}</div>
        <h3 className="mt-2 text-[21px] font-semibold tracking-tight">{title}</h3>
        <p className="mt-2.5 text-[14px] leading-relaxed text-[#6B6B73]">{desc}</p>
      </div>
      <div className="flex min-h-[128px] w-full flex-1 items-center justify-center rounded-xl border border-[#EEEEEE] bg-white p-4">
        {visual}
      </div>
    </div>
  )
}

function DeployCard({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="rounded-xl border border-[#2C2C2C] bg-[#171717] p-4">
      <div className="text-[13px] font-bold text-white">{title}</div>
      <div className="mt-1 text-[11px] text-white">{sub}</div>
    </div>
  )
}

/* 히어로 스크린샷 — 리컬러된 실제 파이프라인 콘솔 화면 (#719) */
function ConsoleShot() {
  return (
    <div className="overflow-hidden rounded-xl border border-white/10 bg-white shadow-[0_30px_80px_rgba(4,5,12,.6)] ring-1 ring-[rgba(120,140,255,.12)]">
      <img
        src="/landing-pipelines.png"
        alt="bifrost 콘솔 — 파이프라인 목록과 AI 에이전트 인시던트 조회"
        className="block w-full"
        width={2032}
        height={440}
        loading="lazy"
      />
    </div>
  )
}
