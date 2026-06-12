import type { Edge, Node } from './types'

export const nodeName = (n: Node): string => n.alias || n.label
export const pipelineLabel = (e: Edge): string => e.alias || e.name

/** pipelines that use a DB as source or sink */
export function dbPipelines(dbId: string, edges: Edge[]): Edge[] {
  return edges.filter((e) => e.source === dbId || e.sink === dbId)
}
