import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import type {
  Edge,
  IncidentReport,
  KafkaSecret,
  KafkaUser,
  Member,
  Node,
  Project,
  Role,
  User,
} from '../data/types'
import {
  BROKERS,
  CLUSTER_CONNECTORS,
  CLUSTER_TOPICS,
  CONSUMER_GROUPS,
  INCIDENTS,
  KAFKA_SECRETS,
  KAFKA_USERS,
  MEMBERS,
} from '../data/mock'
import { api, getToken, setToken, type PipelineCreateInput } from '../lib/api'
import { datasourceToNode, pipelineToEdge, workspaceToProject } from '../lib/mappers'

export type View =
  | 'pipelines'
  | 'pipeline-detail'
  | 'databases'
  | 'database-detail'
  | 'alerts'
  | 'cluster'
  | 'settings'

/** An AI recommended action handed off from an incident to the agent panel. */
export interface AgentTask {
  incidentId: string
  actionId: string
  incidentTitle: string
  label: string
  detail: string
  risk: 'low' | 'medium' | 'high'
  estimatedTime: string
}

/** A navigable position, captured so browser back/forward can restore it. */
interface NavSnapshot {
  projectId: string | null
  view: View
  selectedPipelineId: string | null
  selectedDatabaseId: string | null
  opSelectedIncidentId: string | null
}

export interface AppSettings {
  projectName: string
  timezone: string
  slackWebhook: string
  slackEnabled: boolean
  emailRecipients: string[]
  severity: 'all' | 'warning' | 'error'
  aiAutonomous: boolean
  aiApprovalWait: string
  aiProdLock: boolean
  lagThresholds: { warning: number; critical: number }
}

interface Store {
  /* auth + nav */
  authReady: boolean
  currentUser: User | null
  currentProject: Project | null
  view: View
  selectedPipelineId: string | null
  selectedDatabaseId: string | null
  opSelectedIncidentId: string | null
  login: (email: string, password: string) => Promise<boolean>
  logout: () => void
  setProject: (p: Project | null) => void
  setView: (v: View) => void
  openPipeline: (id: string) => void
  openDatabase: (id: string) => void
  openIncident: (id: string) => void
  /* ai panel */
  aiPanelOpen: boolean
  setAIPanel: (open: boolean) => void
  agentTask: AgentTask | null
  dispatchAgentTask: (task: AgentTask) => void
  consumeAgentTask: () => void
  /* data */
  projects: Project[]
  nodes: Node[]
  edges: Edge[]
  incidents: IncidentReport[]
  members: Member[]
  kafkaUsers: KafkaUser[]
  kafkaSecrets: KafkaSecret[]
  settings: AppSettings
  visibleProjects: Project[]
  /* actions */
  createProject: (name: string) => Promise<Project | null>
  reloadProjectData: () => void
  addDatabaseNode: (n: Node) => void
  createPipeline: (input: PipelineCreateInput) => Promise<Edge | null>
  pausePipeline: (id: string) => Promise<void>
  resumePipeline: (id: string) => Promise<void>
  setPipelineStatus: (id: string, status: Edge['status']) => void
  deletePipeline: (id: string) => Promise<void>
  runIncidentAction: (incidentId: string, actionId: string) => void
  addMember: (email: string, role: Role) => void
  removeMember: (email: string) => void
  changeMemberRole: (email: string, role: Role) => void
  addKafkaUser: (principal: string, auth: KafkaUser['auth'], secret: string) => void
  removeKafkaUser: (id: string) => void
  toggleKafkaUser: (id: string) => void
  revokeSecret: (id: string) => void
  addSecret: (name: string, type: KafkaSecret['type']) => void
  updateSettings: (patch: Partial<AppSettings>) => void
}

const Ctx = createContext<Store | null>(null)
const today = () => new Date().toISOString().slice(0, 10)
const clock = () =>
  new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })

function userFromEmail(email: string): User {
  return {
    name: email.split('@')[0],
    email,
    role: 'developer',
    initial: (email[0] ?? '?').toUpperCase(),
  }
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [currentProject, setCurrentProject] = useState<Project | null>(null)
  const [view, setViewRaw] = useState<View>('pipelines')
  const [selectedPipelineId, setSelectedPipelineId] = useState<string | null>(null)
  const [selectedDatabaseId, setSelectedDatabaseId] = useState<string | null>(null)
  const [opSelectedIncidentId, setOpSelectedIncidentId] = useState<string | null>(null)
  const [aiPanelOpen, setAiPanelOpen] = useState(false)
  const [agentTask, setAgentTask] = useState<AgentTask | null>(null)
  const [authReady, setAuthReady] = useState(false)

  /* 실데이터: 로그인/워크스페이스 선택 시 백엔드에서 로드 */
  const [projects, setProjects] = useState<Project[]>([])
  const [nodes, setNodes] = useState<Node[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  /* 아직 미연동(W2/W3): mock 유지 */
  const [incidents, setIncidents] = useState<IncidentReport[]>(INCIDENTS)
  const [members, setMembers] = useState<Member[]>(MEMBERS)
  const [kafkaUsers, setKafkaUsers] = useState<KafkaUser[]>(KAFKA_USERS)
  const [kafkaSecrets, setKafkaSecrets] = useState<KafkaSecret[]>(KAFKA_SECRETS)
  const [settings, setSettings] = useState<AppSettings>({
    projectName: 'Bifrost',
    timezone: 'Asia/Seoul (GMT+9)',
    slackWebhook: '',
    slackEnabled: false,
    emailRecipients: ['oncall@bifrost.io'],
    severity: 'warning',
    aiAutonomous: false,
    aiApprovalWait: '10분',
    aiProdLock: true,
    lagThresholds: { warning: 5000, critical: 20000 },
  })

  /** A snapshot of the current navigable position, with optional overrides. */
  const snapshot = (o: Partial<NavSnapshot> = {}): NavSnapshot => ({
    projectId: currentProject?.id ?? null,
    view,
    selectedPipelineId,
    selectedDatabaseId,
    opSelectedIncidentId,
    ...o,
  })

  /** Restore a saved position when the browser back/forward buttons fire. */
  const applySnapshot = (s: NavSnapshot) => {
    const proj = s.projectId ? projects.find((p) => p.id === s.projectId) ?? null : null
    setCurrentProject(proj)
    if (proj) loadProjectData(proj.id)
    setViewRaw(s.view)
    setSelectedPipelineId(s.selectedPipelineId)
    setSelectedDatabaseId(s.selectedDatabaseId)
    setOpSelectedIncidentId(s.opSelectedIncidentId)
  }
  const applySnapshotRef = useRef(applySnapshot)
  applySnapshotRef.current = applySnapshot

  /** Add a browser history entry for a new position. */
  const pushNav = (s: NavSnapshot) => window.history.pushState({ bifrostNav: s }, '')
  /** Replace the current browser entry (seeds the first screen after login). */
  const seedNav = (s: NavSnapshot) => window.history.replaceState({ bifrostNav: s }, '')

  useEffect(() => {
    const onPopState = (e: PopStateEvent) => {
      const st = e.state as { bifrostNav?: NavSnapshot } | null
      if (st?.bifrostNav) applySnapshotRef.current(st.bifrostNav)
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  /* 워크스페이스 SSE: pipeline_status_changed로 creating→active 등 실시간 상태 반영(#73). */
  useEffect(() => {
    const wsId = currentProject?.id
    if (!wsId) return
    const es = new EventSource(api.eventStreamUrl(wsId))
    es.addEventListener('pipeline_status_changed', (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data) as { pipelineId: string; status: Edge['status'] }
        setEdges((prev) =>
          prev.map((edge) => (edge.id === data.pipelineId ? { ...edge, status: data.status } : edge)),
        )
      } catch {
        /* malformed payload — ignore */
      }
    })
    // onerror 시 EventSource가 자동 재연결한다(워크스페이스 변경/언마운트 시 close).
    return () => es.close()
  }, [currentProject?.id])

  /* ----------------------------------------------------------- 데이터 로딩 */

  async function loadWorkspaces() {
    try {
      const list = await api.listWorkspaces()
      setProjects(list.map((w) => workspaceToProject(w)))
    } catch {
      setProjects([])
    }
  }

  /* 새로고침 시 세션 복원: 토큰이 있으면 me()로 사용자/워크스페이스를 되살린다(없으면 토큰 폐기). */
  useEffect(() => {
    if (!getToken()) {
      setAuthReady(true)
      return
    }
    let cancelled = false
    api
      .me()
      .then(async (me) => {
        if (cancelled) return
        setCurrentUser(userFromEmail(me.email))
        await loadWorkspaces()
      })
      .catch(() => setToken(null))
      .finally(() => {
        if (!cancelled) setAuthReady(true)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 워크스페이스 선택 시 DB·파이프라인을 백엔드에서 로드하고 project 인덱스를 채운다. */
  async function loadProjectData(wsId: string) {
    try {
      const [dbs, pls] = await Promise.all([api.listDatabases(wsId), api.listPipelines(wsId)])
      const nextNodes = dbs.map((d, i) => datasourceToNode(d, i))
      const nextEdges = pls.map(pipelineToEdge)
      setNodes(nextNodes)
      setEdges(nextEdges)
      const dbIds = nextNodes.map((n) => n.id)
      const pipelineIds = nextEdges.map((e) => e.id)
      setProjects((prev) =>
        prev.map((w) => (w.id === wsId ? { ...w, dbIds, pipelineIds } : w)),
      )
      setCurrentProject((w) => (w && w.id === wsId ? { ...w, dbIds, pipelineIds } : w))
    } catch {
      setNodes([])
      setEdges([])
    }
  }

  const value: Store = {
    authReady,
    currentUser,
    currentProject,
    view,
    selectedPipelineId,
    selectedDatabaseId,
    opSelectedIncidentId,

    async login(email, password) {
      try {
        const tokens = await api.login(email, password)
        setToken(tokens.accessToken)
        const me = await api.me()
        setCurrentUser(userFromEmail(me.email))
        setCurrentProject(null)
        await loadWorkspaces()
        seedNav({
          projectId: null,
          view: 'pipelines',
          selectedPipelineId: null,
          selectedDatabaseId: null,
          opSelectedIncidentId: null,
        })
        return true
      } catch {
        setToken(null)
        return false
      }
    },
    logout() {
      setToken(null)
      setCurrentUser(null)
      setCurrentProject(null)
      setProjects([])
      setNodes([])
      setEdges([])
      setSelectedPipelineId(null)
      setSelectedDatabaseId(null)
      setAiPanelOpen(false)
    },
    setProject(p) {
      setCurrentProject(p)
      if (p) {
        setViewRaw('pipelines')
        setSelectedPipelineId(null)
        setSelectedDatabaseId(null)
        setOpSelectedIncidentId(null)
        loadProjectData(p.id)
      }
      pushNav(
        snapshot({
          projectId: p?.id ?? null,
          view: p ? 'pipelines' : view,
          selectedPipelineId: null,
          selectedDatabaseId: null,
          opSelectedIncidentId: null,
        }),
      )
    },
    setView(v) {
      if (v === view) return
      setViewRaw(v)
      pushNav(snapshot({ view: v }))
    },
    openPipeline(id) {
      setSelectedPipelineId(id)
      setViewRaw('pipeline-detail')
      pushNav(snapshot({ view: 'pipeline-detail', selectedPipelineId: id }))
    },
    openDatabase(id) {
      setSelectedDatabaseId(id)
      setViewRaw('database-detail')
      pushNav(snapshot({ view: 'database-detail', selectedDatabaseId: id }))
    },
    openIncident(id) {
      setOpSelectedIncidentId(id)
      setViewRaw('alerts')
      pushNav(snapshot({ view: 'alerts', opSelectedIncidentId: id }))
    },
    aiPanelOpen,
    setAIPanel(open) {
      setAiPanelOpen(open)
    },
    agentTask,
    dispatchAgentTask(task) {
      setAgentTask(task)
      setAiPanelOpen(true)
    },
    consumeAgentTask() {
      setAgentTask(null)
    },

    projects,
    nodes,
    edges,
    incidents,
    members,
    kafkaUsers,
    kafkaSecrets,
    settings,
    visibleProjects: projects,

    async createProject(name) {
      try {
        const ws = await api.createWorkspace(name)
        const p = workspaceToProject(ws)
        setProjects((prev) => [...prev, p])
        setCurrentProject(p)
        setViewRaw('pipelines')
        setSelectedPipelineId(null)
        setSelectedDatabaseId(null)
        setOpSelectedIncidentId(null)
        setNodes([])
        setEdges([])
        pushNav(
          snapshot({
            projectId: p.id,
            view: 'pipelines',
            selectedPipelineId: null,
            selectedDatabaseId: null,
            opSelectedIncidentId: null,
          }),
        )
        return p
      } catch {
        return null
      }
    },

    reloadProjectData() {
      if (currentProject) loadProjectData(currentProject.id)
    },

    addDatabaseNode(node) {
      setNodes((p) => [...p, node])
      if (currentProject) {
        setProjects((p) =>
          p.map((w) =>
            w.id === currentProject.id ? { ...w, dbIds: [...w.dbIds, node.id] } : w,
          ),
        )
        setCurrentProject((w) => (w ? { ...w, dbIds: [...w.dbIds, node.id] } : w))
      }
    },

    async createPipeline(input) {
      if (!currentProject) return null
      try {
        const created = await api.createPipeline(currentProject.id, input)
        const edge = pipelineToEdge(created)
        setEdges((p) => [...p, edge])
        setProjects((p) =>
          p.map((w) =>
            w.id === currentProject.id ? { ...w, pipelineIds: [...w.pipelineIds, edge.id] } : w,
          ),
        )
        setCurrentProject((w) => (w ? { ...w, pipelineIds: [...w.pipelineIds, edge.id] } : w))
        return edge
      } catch {
        return null
      }
    },

    async pausePipeline(id) {
      if (!currentProject) return
      const p = await api.pausePipeline(currentProject.id, id)
      setEdges((prev) => prev.map((e) => (e.id === id ? { ...e, status: p.status } : e)))
    },
    async resumePipeline(id) {
      if (!currentProject) return
      const p = await api.resumePipeline(currentProject.id, id)
      setEdges((prev) => prev.map((e) => (e.id === id ? { ...e, status: p.status } : e)))
    },

    setPipelineStatus(id, status) {
      setEdges((p) => p.map((e) => (e.id === id ? { ...e, status } : e)))
    },

    async deletePipeline(id) {
      if (currentProject) {
        try {
          await api.deletePipeline(currentProject.id, id)
        } catch {
          return
        }
      }
      setEdges((p) => p.filter((e) => e.id !== id))
      setProjects((p) =>
        p.map((w) => ({ ...w, pipelineIds: w.pipelineIds.filter((x) => x !== id) })),
      )
      setCurrentProject((w) =>
        w ? { ...w, pipelineIds: w.pipelineIds.filter((x) => x !== id) } : w,
      )
    },

    runIncidentAction(incidentId, actionId) {
      setIncidents((p) =>
        p.map((inc) => {
          if (inc.id !== incidentId) return inc
          const action = inc.aiActions.find((a) => a.id === actionId)
          if (!action) return inc
          return {
            ...inc,
            status: 'investigating',
            updatedAt: `${today()} ${clock()}`,
            actionLog: [
              ...inc.actionLog,
              { time: clock(), actor: currentUser?.name ?? 'Operator', action: `Ran: ${action.label}` },
            ],
          }
        }),
      )
    },

    addMember(email, role) {
      setMembers((p) => [
        ...p,
        { name: email.split('@')[0], email, role, joinedAt: today() },
      ])
    },
    removeMember(email) {
      setMembers((p) => p.filter((m) => m.email !== email))
    },
    changeMemberRole(email, role) {
      setMembers((p) => p.map((m) => (m.email === email ? { ...m, role } : m)))
    },

    addKafkaUser(principal, auth, secret) {
      const p = principal.startsWith('User:') ? principal : `User:${principal}`
      setKafkaUsers((prev) => [
        ...prev,
        {
          id: `ku-${Date.now()}`,
          principal: p,
          auth,
          secret,
          acl: { read: true, write: false, admin: false },
          status: 'active',
          lastActive: 'just now',
        },
      ])
    },
    removeKafkaUser(id) {
      setKafkaUsers((p) => p.filter((u) => u.id !== id))
    },
    toggleKafkaUser(id) {
      setKafkaUsers((p) =>
        p.map((u) =>
          u.id === id ? { ...u, status: u.status === 'active' ? 'inactive' : 'active' } : u,
        ),
      )
    },
    revokeSecret(id) {
      setKafkaSecrets((p) => p.map((s) => (s.id === id ? { ...s, status: 'revoked' } : s)))
    },
    addSecret(name, type) {
      setKafkaSecrets((p) => [
        ...p,
        {
          id: `ks-${Date.now()}`,
          name,
          type,
          cluster: 'primary',
          connections: 0,
          lastRotated: today(),
          status: 'active',
        },
      ])
    },
    updateSettings(patch) {
      setSettings((s) => ({ ...s, ...patch }))
    },
  }

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp(): Store {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}

export const CLUSTER = { BROKERS, CLUSTER_TOPICS, CONSUMER_GROUPS, CLUSTER_CONNECTORS }
