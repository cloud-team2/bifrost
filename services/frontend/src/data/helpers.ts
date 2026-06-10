import type { Edge, Node, Project } from './types'

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
