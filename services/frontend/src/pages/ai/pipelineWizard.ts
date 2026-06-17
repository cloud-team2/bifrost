import type {
  CdcReadinessResponse,
  DatabaseResponse,
  PipelineCreateInput,
  SchemaTable,
} from '../../lib/api'

export type PipelineWizardPattern = PipelineCreateInput['pattern']

export interface PipelineWizardTable {
  schema: string
  name: string
}

export interface PipelineWizardSelections {
  pattern: PipelineWizardPattern
  sourceDbId: string
  sinkDbId: string | null
  table: PipelineWizardTable
  name: string
  alias?: string | null
}

const PIPELINE_WORDS = ['파이프라인', 'pipeline']
const CREATE_WORDS = ['생성', '만들', '추가', '연결해', '연결하', 'create', 'new', 'make', 'build']

export function isPipelineWizardIntent(input: string) {
  const normalized = input.trim().toLowerCase()
  if (!normalized) return false
  const hasPipeline = PIPELINE_WORDS.some((word) => normalized.includes(word))
  const hasCreate = CREATE_WORDS.some((word) => normalized.includes(word))
  return hasPipeline && hasCreate
}

export function pipelineWizardStartCandidate(input: string, { running }: { running: boolean }) {
  const message = input.trim()
  if (!message || running || !isPipelineWizardIntent(message)) return null
  return message
}

export function databaseLabel(db: DatabaseResponse) {
  return db.name || db.dbName || db.id
}

export function databaseEndpoint(db: DatabaseResponse) {
  return `${db.host}:${db.port}/${db.dbName}`
}

export function tableKey(table: PipelineWizardTable | SchemaTable) {
  return `${table.schema}.${table.name}`
}

export function suggestPipelineName(
  source: DatabaseResponse | null,
  table: PipelineWizardTable | null,
  pattern: PipelineWizardPattern | null,
  sink: DatabaseResponse | null,
) {
  if (!source || !table || !pattern) return ''
  const base = `${databaseLabel(source)}.${table.name}`
  return pattern === 'direct' && sink
    ? `${base} to ${databaseLabel(sink)}`
    : `${base} fan-out`
}

export function buildPipelineCreateInput(selections: PipelineWizardSelections): PipelineCreateInput {
  return {
    name: selections.name.trim(),
    alias: selections.alias?.trim() ? selections.alias.trim() : null,
    pattern: selections.pattern,
    sourceDbId: selections.sourceDbId,
    sinkDbId: selections.pattern === 'direct' ? selections.sinkDbId : null,
    schema: selections.table.schema,
    table: selections.table.name,
  }
}

export function readinessBlocked(readiness: CdcReadinessResponse | null) {
  if (!readiness) return false
  return readiness.overallStatus === 'BLOCKED' || readiness.checks.some((check) => check.status === 'BLOCKED')
}

export function readinessAllowsCreate(
  source: CdcReadinessResponse | null,
  sink: CdcReadinessResponse | null,
  pattern: PipelineWizardPattern | null,
) {
  if (!source) return false
  if (readinessBlocked(source)) return false
  if (pattern === 'direct') return !!sink && !readinessBlocked(sink)
  return true
}
