export interface ToolCatalogLike {
  name: string
  method: string
  path: string
  risk?: string
  params_schema?: {
    required?: string[]
  }
}

export interface SlashToolCommand {
  slug: string
  label: string
  toolName: string
  path: string
  description: string
  pathParams: string[]
  argParams: string[]
  usage: string
}

export interface ParsedSlashCommand {
  command: SlashToolCommand
  args: string[]
}

const PROJECT_PARAM_NAMES = new Set(['projectId', 'project_id'])
const PATH_UTILITY_SEGMENTS = new Set(['status', 'summary', 'lag'])
const TOOL_VERB_RE = /^(get|list|query|analyze)_/

export function buildSlashCommands(
  tools: ToolCatalogLike[],
  descriptions: Record<string, string> = {},
): SlashToolCommand[] {
  const usedSlugs = new Set<string>()
  return tools
    .flatMap((tool): SlashToolCommand[] => {
      if (tool.risk && tool.risk !== 'read_only') return []
      const pathParams = nonProjectPathParams(tool.path)
      const requiredParams = (tool.params_schema?.required ?? []).filter((param) => !pathParams.includes(param))
      const argParams = [...pathParams, ...requiredParams]
      const slug = uniqueSlug(
        preferredSlug(tool) ?? slugFromPath(tool.path, argParams.length > 0) ?? slugFromToolName(tool.name),
        usedSlugs,
      )
      usedSlugs.add(slug)
      return [{
        slug,
        label: `/${slug}`,
        toolName: tool.name,
        path: tool.path,
        description: descriptions[tool.name] ?? humanizeToolName(tool.name),
        pathParams,
        argParams,
        usage: `/${slug}${argParams.map((param) => ` <${param}>`).join('')}`,
      }]
    })
    .sort((a, b) => a.slug.localeCompare(b.slug))
}

export function slashSearch(value: string) {
  const token = value.trimStart()
  if (!token.startsWith('/')) return null
  const first = token.split(/\s+/, 1)[0]
  return first.slice(1).toLowerCase()
}

export function parseSlashCommand(value: string, commands: SlashToolCommand[]): ParsedSlashCommand | null {
  const parts = value.trim().split(/\s+/).filter(Boolean)
  const first = parts[0]?.toLowerCase()
  if (!first?.startsWith('/')) return null
  const command = commands.find((item) => item.label === first)
  return command ? { command, args: parts.slice(1) } : null
}

export function missingSlashArgs(command: SlashToolCommand, args: string[]) {
  return command.argParams.slice(args.length)
}

export function slashCommandParams(command: SlashToolCommand, args: string[]) {
  return command.argParams.reduce<Record<string, unknown>>((params, param, index) => {
    if (!args[index]) return params
    params[param] = index === command.argParams.length - 1 && args.length > command.argParams.length
      ? args.slice(index).join(' ')
      : args[index]
    return params
  }, {})
}

function nonProjectPathParams(path: string) {
  return [...path.matchAll(/\{([^}]+)\}/g)]
    .map((match) => match[1])
    .filter((param) => !PROJECT_PARAM_NAMES.has(param))
}

function slugFromPath(path: string, hasRequiredArgs: boolean) {
  const segments = path.split('/').filter(Boolean)
  const projectIndex = segments.findIndex((segment) => segment === 'projects')
  if (projectIndex < 0 || projectIndex + 2 >= segments.length) return null
  const tail = segments.slice(projectIndex + 2)
  const meaningful = tail.filter(
    (segment) =>
      !segment.startsWith('{') &&
      (hasRequiredArgs || !PATH_UTILITY_SEGMENTS.has(segment)) &&
      segment !== 'kafka' &&
      segment !== 'observability',
  )
  if (meaningful.length === 0) return null
  if (hasRequiredArgs) return meaningful.join('-')
  return meaningful.at(-1) ?? null
}

function preferredSlug(tool: ToolCatalogLike) {
  if (tool.name === 'list_pipelines') return 'pipelines'
  if (tool.name === 'list_project_pipelines') return 'pipelines-list'
  return null
}

function slugFromToolName(name: string) {
  return name.replace(TOOL_VERB_RE, '').replaceAll('_', '-')
}

function uniqueSlug(slug: string, usedSlugs: Set<string>) {
  if (!usedSlugs.has(slug)) return slug
  let suffix = 2
  while (usedSlugs.has(`${slug}-${suffix}`)) suffix += 1
  return `${slug}-${suffix}`
}

function humanizeToolName(name: string) {
  return name.replace(TOOL_VERB_RE, '').replaceAll('_', ' ')
}
