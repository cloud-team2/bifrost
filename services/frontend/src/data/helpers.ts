import type { Edge, Node, Point, Project } from './types'
import { LAG_THRESHOLD } from './mock'

export const nodeName = (n: Node): string => n.alias || n.label
export const pipelineLabel = (e: Edge): string => e.alias || e.name

/** consumer service nodes subscribed to a fan-out pipeline */
export function pipelineConsumers(edge: Edge, nodes: Node[]): Node[] {
  if (edge.pattern !== 'fan-out') return []
  return (edge.consumers ?? [])
    .map((id) => nodes.find((n) => n.id === id))
    .filter((n): n is Node => Boolean(n))
}

/** whether a DB acts as a source / sink across the given pipelines */
export function dbRoles(dbId: string, edges: Edge[]): { isSource: boolean; isSink: boolean } {
  return {
    isSource: edges.some((e) => e.source === dbId),
    isSink: edges.some((e) => e.sink === dbId),
  }
}

/** pipelines that use a DB as source or sink */
export function dbPipelines(dbId: string, edges: Edge[]): Edge[] {
  return edges.filter((e) => e.source === dbId || e.sink === dbId)
}

export interface TopoLink {
  id: string
  from: string
  to: string
  edge: Edge
}

/** flatten pipelines into directed topology links (source → consumer | sink) */
export function topoLinks(edges: Edge[], nodes: Node[]): TopoLink[] {
  const ids = new Set(nodes.map((n) => n.id))
  const links: TopoLink[] = []
  for (const e of edges) {
    if (!ids.has(e.source)) continue
    const targets = e.pattern === 'direct' ? (e.sink ? [e.sink] : []) : e.consumers ?? []
    targets.forEach((t, i) => {
      if (ids.has(t)) links.push({ id: `${e.id}-${i}`, from: e.source, to: t, edge: e })
    })
  }
  return links
}

/** every node that participates in a project (DBs + pipeline consumers/sinks) */
export function projectNodes(proj: Project, nodes: Node[], edges: Edge[]): Node[] {
  const ids = new Set<string>(proj.dbIds)
  for (const e of edges.filter((x) => proj.pipelineIds.includes(x.id))) {
    ids.add(e.source)
    if (e.sink) ids.add(e.sink)
    ;(e.consumers ?? []).forEach((c) => ids.add(c))
  }
  return nodes.filter((n) => ids.has(n.id))
}

export function projectEdges(proj: Project, edges: Edge[]): Edge[] {
  return edges.filter((e) => proj.pipelineIds.includes(e.id))
}

/** consumer node health from lag + group state (spec §3.6) */
export function deriveConsumerStatus(lag: number, group?: string): Node['status'] {
  if (group === 'DEAD') return 'error'
  if (lag >= LAG_THRESHOLD || group === 'REBALANCING') return 'warning'
  return 'healthy'
}

/* ----------------------------------------------------------- chart series */

let seed = 7
function rnd(): number {
  seed = (seed * 1664525 + 1013904223) % 4294967296
  return seed / 4294967296
}

export function timeLabels(n: number, stepMin = 5): string[] {
  const out: string[] = []
  const now = new Date()
  now.setSeconds(0, 0)
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(now.getTime() - i * stepMin * 60_000)
    out.push(`${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`)
  }
  return out
}

/** deterministic time-series for recharts */
export function genSeries(
  keys: { key: string; base: number; vary: number; drift?: number }[],
  n = 24,
): Point[] {
  seed = 7
  const labels = timeLabels(n)
  return labels.map((t, i) => {
    const p: Point = { t }
    for (const k of keys) {
      const wave = Math.sin(i / 3) * k.vary * 0.4
      const noise = (rnd() - 0.5) * k.vary
      const drift = (k.drift ?? 0) * i
      p[k.key] = Math.max(0, Math.round(k.base + wave + noise + drift))
    }
    return p
  })
}
