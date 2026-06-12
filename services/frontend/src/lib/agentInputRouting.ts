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
      message: missingSlashArgsMessage(missing),
      input: slashInputWithProvidedArgs(parsed),
    }
  }
  return { kind: 'slash_execute', parsed }
}

function slashInputWithProvidedArgs(parsed: ParsedSlashCommand) {
  return `${parsed.command.label}${parsed.args.length > 0 ? ` ${parsed.args.join(' ')}` : ''} `
}

function missingSlashArgsMessage(missing: string[]) {
  const param = missing[0]
  if (param.endsWith('_name') || param.endsWith('Name')) return `찾아보고싶은 ${param}을 알려주세요`
  if (param.endsWith('_id') || param.endsWith('Id')) return `조회할 ${param}를 알려주세요`
  return `입력할 ${param} 값을 알려주세요`
}
