import { useEffect, useState } from 'react'
import { useApp, type View } from './store/AppStore'
import { Login } from './pages/Login'
import { Register } from './pages/Register'
import { ProjectListView } from './pages/Projects'
import { ConsoleShell } from './components/shell/ConsoleShell'
import { Pipelines } from './pages/dev/Pipelines'
import { PipelineDetail } from './pages/dev/PipelineDetail'
import { Databases } from './pages/dev/Databases'
import { DatabaseDetail } from './pages/dev/DatabaseDetail'
import { Settings } from './pages/Settings'
import { Alerts } from './pages/Alerts'
import { OperatorCluster } from './pages/op/OperatorCluster'
import { OperatorOverview } from './pages/op/OperatorOverview'

const VIEW_LABEL: Record<View, string> = {
  overview: 'overview',
  pipelines: 'pipelines',
  'pipeline-detail': 'pipelines / detail',
  databases: 'databases',
  'database-detail': 'databases / detail',
  alerts: 'alerts',
  cluster: 'cluster',
  settings: 'settings',
}

export default function App() {
  const app = useApp()
  const [authView, setAuthView] = useState<'login' | 'register'>('login')

  useEffect(() => {
    if (app.currentUser) setAuthView('login')
  }, [app.currentUser])

  // 세션 복원(토큰→me()) 완료 전엔 로그인 화면 깜빡임을 피하려 빈 화면 유지.
  if (!app.authReady) return null
  if (!app.currentUser) {
    return authView === 'register'
      ? <Register onSignIn={() => setAuthView('login')} />
      : <Login onRegister={() => setAuthView('register')} />
  }
  if (!app.currentProject) return <ProjectListView />

  return <ConsoleShell viewLabel={VIEW_LABEL[app.view] ?? app.view}>{renderView(app.view)}</ConsoleShell>
}

function renderView(view: View) {
  switch (view) {
    case 'overview':       return <OperatorOverview />
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
