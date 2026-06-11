import {
  missingSlashArgs,
  parseSlashCommand,
  type ParsedSlashCommand,
  type SlashToolCommand,
} from './slashCommands'

export type AgentInputRoute =
  | { kind: 'empty' }
  | { kind: 'free_text'; message: string }
  | { kind: 'slash_loading'; message: string }
  | { kind: 'slash_error'; message: string }
  | { kind: 'slash_unknown'; message: string }
  | { kind: 'slash_missing_args'; message: string; input: string }
  | { kind: 'slash_execute'; parsed: ParsedSlashCommand }

export function routeAgentInput(
  value: string,
  options: {
    slashCommands: boolean
    slashLoading: boolean
    slashError: string | null
    commands: SlashToolCommand[]
  },
): AgentInputRoute {
  const message = value.trim()
  if (!message) return { kind: 'empty' }
  if (!options.slashCommands || !message.startsWith('/')) return { kind: 'free_text', message }
  if (options.slashLoading) return { kind: 'slash_loading', message: 'tool catalog를 불러오는 중입니다.' }
  if (options.slashError) return { kind: 'slash_error', message: options.slashError }

  const parsed = parseSlashCommand(message, options.commands)
  if (!parsed) return { kind: 'slash_unknown', message: `알 수 없는 slash command입니다: ${message.split(/\s+/)[0]}` }

  const missing = missingSlashArgs(parsed.command, parsed.args)
  if (missing.length > 0) {
    return {
      kind: 'slash_missing_args',
      message: missingSlashArgsMessage(missing, parsed.command.usage),
      input: `${parsed.command.label} `,
    }
  }
  return { kind: 'slash_execute', parsed }
}

function missingSlashArgsMessage(missing: string[], usage: string) {
  const params = missing.join(', ')
  const subject = missing.length === 1 ? `${params}을` : `${params} 값을`
  return `${subject} 입력해주세요 (사용법: ${usage})`
}
