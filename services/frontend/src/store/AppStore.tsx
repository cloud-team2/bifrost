import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import type {
  Edge,
  Node,
  Project,
  User,
} from '../data/types'
import {
  api,
  getToken,
  setToken,
  type ActionRunCandidateInput,
  type AuthTokens,
  type EventResponse,
  type IncidentResponse,
  type MeResponse,
  type PipelineCreateInput,
  type RegisterInput,
  type ResourceEventResponse,
} from '../lib/api'
import { datasourceToNode, pipelineToEdge, workspaceToProject } from '../lib/mappers'

export type View =
  | 'pipelines'
  | 'pipeline-detail'
  | 'databases'
  | 'database-detail'
  | 'alerts'
  | 'cluster'
  | 'settings'


export type AgentRunSseStatus = 'idle' | 'starting' | 'running' | 'waiting_for_approval' | 'completed' | 'failed'

export interface AgentRunSseState {
  runId: string | null
  status: AgentRunSseStatus
  lastEventType: string | null
  lastMessage: string | null
  updatedAt: string | null
}

/** An AI recommended action handed off from an incident to the agent panel. */
export interface AgentTask {
  incidentId: string
  actionId: string
  incidentTitle: string
  label: string
  detail: string
  risk: ActionRunCandidateInput['risk']
  estimatedTime: string
  actionCandidate: ActionRunCandidateInput
}

/** A navigable position, captured so browser back/forward can restore it. */
interface NavSnapshot {
  projectId: string | null
  view: View
  selectedPipelineId: string | null
  selectedDatabaseId: string | null
  opSelectedIncidentId: string | null
}

interface Store {
  /* auth + nav */
  authReady: boolean
  currentUser: User | null
  currentProject: Project | null
  view: View
  selectedPipelineId: string | null
  selectedTraceId: string | null
  pipelineTab: string | null
  selectedDatabaseId: string | null
  opSelectedIncidentId: string | null
  login: (email: string, password: string) => Promise<boolean>
  register: (input: RegisterInput) => Promise<void>
  logout: () => void
  setProject: (p: Project | null) => void
  setView: (v: View) => void
  openPipeline: (id: string) => void
  openPipelineTrace: (pipelineId: string, traceId: string) => void
  openDatabase: (id: string) => void
  openIncident: (id: string) => void
  clearOpSelectedIncident: () => void
  /* ai panel */
  aiPanelOpen: boolean
  setAIPanel: (open: boolean) => void
  agentTask: AgentTask | null
  dispatchAgentTask: (task: AgentTask) => void
  consumeAgentTask: () => void
  agentRunState: AgentRunSseState
  setAgentRunState: (patch: Partial<AgentRunSseState>) => void
  /* data */
  projects: Project[]
  nodes: Node[]
  edges: Edge[]
  incidents: IncidentResponse[]
  events: EventResponse[]
  resourceEvents: ResourceEventResponse[]
  monitoringLoading: boolean
  monitoringError: string | null
  visibleProjects: Project[]
  /* actions */
  createProject: (name: string) => Promise<Project | null>
  reloadProjectData: () => void
  refreshDatabaseNode: (id: string) => Promise<void>
  addDatabaseNode: (n: Node) => void
  deleteDatabase: (id: string) => Promise<void>
  createPipeline: (input: PipelineCreateInput) => Promise<Edge | null>
  pausePipeline: (id: string) => Promise<void>
  resumePipeline: (id: string) => Promise<void>
  setPipelineStatus: (id: string, status: Edge['status']) => void
  deletePipeline: (id: string) => Promise<void>
  runIncidentAction: (incidentId: string, actionId: string) => void
  reloadMonitoring: () => Promise<void>
}

interface LoadMonitoringOptions {
  showLoading?: boolean
  clearOnError?: boolean
}

const Ctx = createContext<Store | null>(null)

const emptyAgentRunState = (): AgentRunSseState => ({
  runId: null,
  status: 'idle',
  lastEventType: null,
  lastMessage: null,
  updatedAt: null,
})

const VIEWS = new Set<View>([
  'pipelines',
  'pipeline-detail',
  'databases',
  'database-detail',
  'alerts',
  'cluster',
  'settings',
])

function normalizeView(view: unknown): View {
  return typeof view === 'string' && VIEWS.has(view as View) ? (view as View) : 'pipelines'
}

function userFromMe(me: MeResponse): User {
  const name = me.name?.trim() || me.email.split('@')[0]
  const initial = Array.from(name)[0] ?? Array.from(me.email)[0] ?? '?'
  return {
    name,
    email: me.email,
    role: me.role,
    initial: initial.toUpperCase(),
    joinedAt: me.joinedAt,
    lastLoginAt: me.lastLoginAt,
  }
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [currentProject, setCurrentProject] = useState<Project | null>(null)
  const currentProjectIdRef = useRef<string | null>(null)
  const projectLoadIdRef = useRef(0)
  const databaseRefreshIdRef = useRef<Record<string, number>>({})
  const monitoringLoadIdRef = useRef(0)
  const [view, setViewRaw] = useState<View>('pipelines')
  const [selectedPipelineId, setSelectedPipelineId] = useState<string | null>(null)
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null)
  const [pipelineTab, setPipelineTab] = useState<string | null>(null)
  const [selectedDatabaseId, setSelectedDatabaseId] = useState<string | null>(null)
  const [opSelectedIncidentId, setOpSelectedIncidentId] = useState<string | null>(null)
  const [aiPanelOpen, setAiPanelOpen] = useState(false)
  const [agentTask, setAgentTask] = useState<AgentTask | null>(null)
  const [agentRunState, setAgentRunStateRaw] = useState<AgentRunSseState>(() => emptyAgentRunState())
  const [authReady, setAuthReady] = useState(false)

  /* 실데이터: 로그인/워크스페이스 선택 시 백엔드에서 로드 */
  const [projects, setProjects] = useState<Project[]>([])
  const [nodes, setNodes] = useState<Node[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [incidents, setIncidents] = useState<IncidentResponse[]>([])
  const [events, setEvents] = useState<EventResponse[]>([])
  const [resourceEvents, setResourceEvents] = useState<ResourceEventResponse[]>([])
  const [monitoringLoading, setMonitoringLoading] = useState(false)
  const [monitoringError, setMonitoringError] = useState<string | null>(null)
  const viewRef = useRef<View>('pipelines')
  const incidentLoadIdRef = useRef(0)

  const selectProject = (project: Project | null) => {
    currentProjectIdRef.current = project?.id ?? null
    setCurrentProject(project)
  }

  const clearMonitoringData = () => {
    monitoringLoadIdRef.current += 1
    incidentLoadIdRef.current += 1
    setIncidents([])
    setEvents([])
    setResourceEvents([])
    setMonitoringLoading(false)
    setMonitoringError(null)
  }

  useEffect(() => {
    currentProjectIdRef.current = currentProject?.id ?? null
  }, [currentProject?.id])

  useEffect(() => {
    viewRef.current = view
  }, [view])

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
    const nextProjectId = proj?.id ?? null
    const projectChanged = currentProjectIdRef.current !== nextProjectId
    selectProject(proj)
    if (projectChanged) clearMonitoringData()
    if (proj) loadProjectData(proj.id)
    setViewRaw(normalizeView(s.view))
    setSelectedPipelineId(s.selectedPipelineId)
    setSelectedDatabaseId(s.selectedDatabaseId)
    setOpSelectedIncidentId(s.opSelectedIncidentId)
    setSelectedTraceId(null)
    setPipelineTab(null)
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
    const refreshMonitoring = () => {
      if (viewRef.current !== 'alerts') return
      void loadMonitoringData(wsId, { showLoading: false, clearOnError: false })
    }
    es.addEventListener('pipeline_status_changed', (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data) as { pipelineId: string; status: Edge['status'] }
        setEdges((prev) =>
          prev.map((edge) => (edge.id === data.pipelineId ? { ...edge, status: data.status } : edge)),
        )
        refreshMonitoring()
      } catch {
        /* malformed payload — ignore */
      }
    })
    const onIncident = (e: Event) => {
      try {
        const data = JSON.parse((e as MessageEvent).data) as IncidentResponse
        setIncidents((prev) => {
          const exists = prev.some((incident) => incident.id === data.id)
          const next = exists
            ? prev.map((incident) => (incident.id === data.id ? data : incident))
            : [data, ...prev]
          return next.sort((a, b) => b.openedAt.localeCompare(a.openedAt))
        })
        refreshMonitoring()
      } catch {
        /* malformed payload — ignore */
      }
    }
    es.addEventListener('connector_state_changed', refreshMonitoring)
    es.addEventListener('incident_opened', onIncident)
    es.addEventListener('incident_updated', onIncident)
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

  async function loadMonitoringData(wsId: string, options: LoadMonitoringOptions = {}) {
    const targetWsId = wsId
    if (currentProjectIdRef.current !== targetWsId) return
    const { showLoading = true, clearOnError = true } = options
    const loadId = ++monitoringLoadIdRef.current
    const isActiveLoad = () =>
      monitoringLoadIdRef.current === loadId && currentProjectIdRef.current === targetWsId

    if (showLoading) setMonitoringLoading(true)
    setMonitoringError(null)
    try {
      const [incidentRows, eventRows, resourceRows] = await Promise.all([
        api.listIncidents(targetWsId),
        api.listEvents(targetWsId),
        api.listResourceEvents(targetWsId),
      ])
      if (!isActiveLoad()) return
      setIncidents(incidentRows)
      setEvents(eventRows)
      setResourceEvents(resourceRows)
    } catch (e) {
      if (!isActiveLoad()) return
      if (clearOnError) {
        setIncidents([])
        setEvents([])
        setResourceEvents([])
      }
      setMonitoringError(e instanceof Error ? e.message : '모니터링 데이터를 불러오지 못했습니다')
    } finally {
      if (isActiveLoad()) setMonitoringLoading(false)
    }
  }

  async function loadIncidentData(wsId: string) {
    const targetWsId = wsId
    if (currentProjectIdRef.current !== targetWsId) return
    const loadId = ++incidentLoadIdRef.current
    const isActiveLoad = () =>
      incidentLoadIdRef.current === loadId && currentProjectIdRef.current === targetWsId

    setMonitoringError(null)
    try {
      const incidentRows = await api.listIncidents(targetWsId)
      if (isActiveLoad()) setIncidents(incidentRows)
    } catch (e) {
      if (isActiveLoad()) setMonitoringError(e instanceof Error ? e.message : '인시던트 데이터를 불러오지 못했습니다')
    }
  }

  useEffect(() => {
    const wsId = currentProject?.id
    if (!wsId || view !== 'alerts') return
    void loadMonitoringData(wsId, { showLoading: false, clearOnError: false })
    const timer = window.setInterval(() => {
      void loadMonitoringData(wsId, { showLoading: false, clearOnError: false })
    }, 10000)
    return () => window.clearInterval(timer)
  }, [currentProject?.id, view])

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
        setCurrentUser(userFromMe(me))
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
    const loadId = ++projectLoadIdRef.current
    const isActiveLoad = () =>
      projectLoadIdRef.current === loadId && currentProjectIdRef.current === wsId
    try {
      const [dbs, pls] = await Promise.all([api.listDatabases(wsId), api.listPipelines(wsId)])
      if (!isActiveLoad()) return
      const nextNodes = dbs.map((d, i) => datasourceToNode(d, i))
      const nextEdges = pls.map(pipelineToEdge)
      setNodes(nextNodes)
      setEdges(nextEdges)
      const dbIds = nextNodes.map((n) => n.id)
      const pipelineIds = nextEdges.map((e) => e.id)
      // 카드 요약 카운트를 방금 로드한 edges로 재동기화(목록 복귀 시 최신 반영, #105)
      const pipelineCount = nextEdges.length
      const activeCount = nextEdges.filter((e) => e.status === 'active').length
      setProjects((prev) =>
        prev.map((w) => (w.id === wsId ? { ...w, dbIds, pipelineIds, pipelineCount, activeCount } : w)),
      )
      setCurrentProject((w) =>
        w && w.id === wsId ? { ...w, dbIds, pipelineIds, pipelineCount, activeCount } : w,
      )
    } catch {
      if (!isActiveLoad()) return
      setNodes([])
      setEdges([])
    }
    if (isActiveLoad()) await loadMonitoringData(wsId)
    if (isActiveLoad()) await loadIncidentData(wsId)
  }

  async function applyAuth(tokens: AuthTokens) {
    setToken(tokens.accessToken)
    const me = await api.me()
    setCurrentUser(userFromMe(me))
    selectProject(null)
    clearMonitoringData()
    await loadWorkspaces()
    seedNav({
      projectId: null,
      view: 'pipelines',
      selectedPipelineId: null,
      selectedDatabaseId: null,
      opSelectedIncidentId: null,
    })
  }

  const value: Store = {
    authReady,
    currentUser,
    currentProject,
    view,
    selectedPipelineId,
    selectedTraceId,
    pipelineTab,
    selectedDatabaseId,
    opSelectedIncidentId,

    async login(email, password) {
      try {
        const tokens = await api.login(email, password)
        await applyAuth(tokens)
        return true
      } catch {
        setToken(null)
        return false
      }
    },
    async register(input) {
      try {
        const tokens = await api.register(input)
        await applyAuth(tokens)
      } catch (e) {
        setToken(null)
        throw e
      }
    },
    logout() {
      setToken(null)
      setCurrentUser(null)
      selectProject(null)
      setProjects([])
      setNodes([])
      setEdges([])
      clearMonitoringData()
      setSelectedPipelineId(null)
      setSelectedDatabaseId(null)
      setSelectedTraceId(null)
      setPipelineTab(null)
      setAiPanelOpen(false)
      setAgentRunStateRaw(emptyAgentRunState())
    },
    setProject(p) {
      const nextProjectId = p?.id ?? null
      const projectChanged = currentProjectIdRef.current !== nextProjectId
      selectProject(p)
      if (projectChanged) clearMonitoringData()
      if (p) {
        setViewRaw('pipelines')
        setSelectedPipelineId(null)
        setSelectedDatabaseId(null)
        setOpSelectedIncidentId(null)
        setSelectedTraceId(null)
        setPipelineTab(null)
        setAgentRunStateRaw(emptyAgentRunState())
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
      setSelectedTraceId(null)
      setPipelineTab(null)
      setViewRaw('pipeline-detail')
      pushNav(snapshot({ view: 'pipeline-detail', selectedPipelineId: id }))
    },
    openPipelineTrace(pipelineId, traceId) {
      setSelectedPipelineId(pipelineId)
      setSelectedTraceId(traceId)
      setPipelineTab('Tracing')
      setViewRaw('pipeline-detail')
      pushNav(snapshot({ view: 'pipeline-detail', selectedPipelineId: pipelineId }))
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
    clearOpSelectedIncident() {
      setOpSelectedIncidentId(null)
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
    agentRunState,
    setAgentRunState(patch) {
      setAgentRunStateRaw((prev) => ({ ...prev, ...patch, updatedAt: new Date().toISOString() }))
    },

    projects,
    nodes,
    edges,
    incidents,
    events,
    resourceEvents,
    monitoringLoading,
    monitoringError,
    visibleProjects: projects,

    async createProject(name) {
      try {
        const ws = await api.createWorkspace(name)
        const p = workspaceToProject(ws)
        setProjects((prev) => [...prev, p])
        selectProject(p)
        setViewRaw('pipelines')
        setSelectedPipelineId(null)
        setSelectedDatabaseId(null)
        setOpSelectedIncidentId(null)
        setNodes([])
        setEdges([])
        clearMonitoringData()
        setAgentRunStateRaw(emptyAgentRunState())
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

    async refreshDatabaseNode(id) {
      const wsId = currentProjectIdRef.current
      if (!wsId) return
      const projectLoadId = projectLoadIdRef.current
      const requestId = (databaseRefreshIdRef.current[id] ?? 0) + 1
      databaseRefreshIdRef.current[id] = requestId
      const db = await api.getDatabase(wsId, id)
      if (
        currentProjectIdRef.current !== wsId
        || projectLoadIdRef.current !== projectLoadId
        || databaseRefreshIdRef.current[id] !== requestId
      ) return
      setNodes((prev) =>
        prev.map((node, index) => {
          if (node.id !== id) return node
          const next = datasourceToNode(db, index)
          return { ...next, x: node.x, y: node.y }
        }),
      )
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

    async deleteDatabase(id) {
      if (!currentProject) return
      try {
        await api.deleteDatabase(currentProject.id, id)
      } catch {
        return
      }
      setNodes((p) => p.filter((n) => n.id !== id))
      setProjects((p) =>
        p.map((w) => ({ ...w, dbIds: w.dbIds.filter((x) => x !== id) })),
      )
      setCurrentProject((w) => (w ? { ...w, dbIds: w.dbIds.filter((x) => x !== id) } : w))
      setSelectedDatabaseId(null)
      setViewRaw('databases')
    },

    async createPipeline(input) {
      if (!currentProject) return null
      try {
        const created = await api.createPipeline(currentProject.id, input)
        const edge = pipelineToEdge(created)
        setEdges((p) => [...p, edge])
        const addActive = edge.status === 'active' ? 1 : 0
        setProjects((p) =>
          p.map((w) =>
            w.id === currentProject.id
              ? {
                  ...w,
                  pipelineIds: [...w.pipelineIds, edge.id],
                  pipelineCount: w.pipelineCount + 1,
                  activeCount: w.activeCount + addActive,
                }
              : w,
          ),
        )
        setCurrentProject((w) =>
          w
            ? {
                ...w,
                pipelineIds: [...w.pipelineIds, edge.id],
                pipelineCount: w.pipelineCount + 1,
                activeCount: w.activeCount + addActive,
              }
            : w,
        )
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
        } catch (e) {
          throw e
        }
      }
      const removed = edges.find((e) => e.id === id)
      const delActive = removed?.status === 'active' ? 1 : 0
      setEdges((p) => p.filter((e) => e.id !== id))
      const adjust = (w: Project): Project =>
        w.pipelineIds.includes(id)
          ? {
              ...w,
              pipelineIds: w.pipelineIds.filter((x) => x !== id),
              pipelineCount: Math.max(0, w.pipelineCount - 1),
              activeCount: Math.max(0, w.activeCount - delActive),
            }
          : w
      setProjects((p) => p.map(adjust))
      setCurrentProject((w) => (w ? adjust(w) : w))
    },

    runIncidentAction() {
      if (currentProject) {
        void loadMonitoringData(currentProject.id, { clearOnError: false })
      }
    },

    async reloadMonitoring() {
      if (currentProject) await loadMonitoringData(currentProject.id, { clearOnError: false })
    },
  }

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp(): Store {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}
