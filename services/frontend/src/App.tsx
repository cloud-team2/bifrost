import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useApp, type View } from './store/AppStore'
import { Login } from './pages/Login'
import { Register } from './pages/Register'

// 랜딩(three.js 포함)은 비로그인 진입 경로 전용 → lazy 로드로 콘솔 번들에서 분리.
const Landing = lazy(() => import('./pages/Landing').then((m) => ({ default: m.Landing })))
import { ProjectListView } from './pages/Projects'
import { ConsoleShell } from './components/shell/ConsoleShell'
import { Pipelines } from './pages/dev/Pipelines'
import { PipelineDetail } from './pages/dev/PipelineDetail'
import { Databases } from './pages/dev/Databases'
import { DatabaseDetail } from './pages/dev/DatabaseDetail'
import { Settings } from './pages/Settings'
import { Alerts } from './pages/Alerts'
import { OperatorCluster } from './pages/op/OperatorCluster'

const VIEW_LABEL: Record<View, string> = {
  pipelines: 'pipelines',
  'pipeline-detail': 'pipelines / detail',
  databases: 'databases',
  'database-detail': 'databases / detail',
  alerts: 'alerts',
  cluster: 'cluster',
  settings: 'settings',
}

type AuthView = 'landing' | 'login' | 'register'

export default function App() {
  const app = useApp()
  const [authView, setAuthView] = useState<AuthView>('landing')

  // popstate가 항상 최신 로그인 상태를 보도록 ref를 렌더마다 동기화.
  const loggedInRef = useRef(false)
  loggedInRef.current = !!app.currentUser

  // 인증 플로우(랜딩→로그인→회원가입)에 새 history 엔트리 추가({bifrostAuth} 태그).
  // AppStore는 콘솔 내비를 {bifrostNav}로 관리 — 태그를 나눠 충돌 없이 공존.
  const goAuth = (next: AuthView) => {
    setAuthView(next)
    window.history.pushState({ bifrostAuth: next }, '')
  }

  // 비로그인 상태의 뒤로/앞으로만 여기서 처리. 로그인 상태의 콘솔 내비는 AppStore가 맡는다.
  useEffect(() => {
    const onPop = (e: PopStateEvent) => {
      if (loggedInRef.current) return // 로그인 중: 콘솔 내비는 AppStore가 처리.
      const st = e.state as { bifrostAuth?: AuthView } | null
      // 비로그인: 인증 화면만 유효. 콘솔 잔재({bifrostNav})·빈 상태는 전부 랜딩으로 귀결.
      setAuthView(st?.bifrostAuth ?? 'landing')
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // 로그인/로그아웃 경계에서 진입점과 history를 정리한다.
  useEffect(() => {
    if (app.currentUser) {
      // 로그인: 콘솔이 렌더 우선(아래)이라 authView는 의미 없음 — 진입점만 랜딩으로 리셋.
      // history는 AppStore.applyAuth의 seedNav가 현재 엔트리를 콘솔로 교체해 정리한다.
      setAuthView('landing')
    } else {
      // 로그아웃/비로그인: 진입점 랜딩 + 현재 엔트리를 랜딩으로 교체해 콘솔 잔재를 무력화.
      // (브라우저는 뒤로-스택 자체를 지울 수 없어, 잔재는 위 popstate 핸들러가 랜딩으로 흡수.)
      setAuthView('landing')
      window.history.replaceState({ bifrostAuth: 'landing' }, '')
    }
  }, [app.currentUser])

  // 세션 복원(토큰→me()) 완료 전엔 화면 깜빡임을 피하려 빈 화면 유지.
  if (!app.authReady) return null
  if (!app.currentUser) {
    if (authView === 'landing')
      return (
        <Suspense fallback={null}>
          <Landing onEnter={() => goAuth('login')} />
        </Suspense>
      )
    return authView === 'register'
      ? <Register onSignIn={() => goAuth('login')} />
      : <Login onRegister={() => goAuth('register')} />
  }
  if (!app.currentProject) return <ProjectListView />

  return <ConsoleShell viewLabel={VIEW_LABEL[app.view] ?? app.view}>{renderView(app.view)}</ConsoleShell>
}

function renderView(view: View) {
  switch (view) {
    case 'pipelines':      return <Pipelines />
    case 'pipeline-detail': return <PipelineDetail />
    case 'databases':      return <Databases />
    case 'database-detail': return <DatabaseDetail />
    case 'alerts':         return <Alerts />
    case 'cluster':        return <OperatorCluster />
    case 'settings':       return <Settings />
    default:               return <Pipelines />
  }
}
