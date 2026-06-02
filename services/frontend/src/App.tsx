import { useApp, type View } from './store/AppStore'
import { Login } from './pages/Login'
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

export default function App() {
  const app = useApp()

  if (!app.currentUser) return <Login />
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
