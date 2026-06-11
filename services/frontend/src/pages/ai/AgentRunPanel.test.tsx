import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { SlashCommandOptionContent } from './AgentRunPanel'
import type { SlashToolCommand } from '../../lib/slashCommands'

describe('SlashCommandOptionContent', () => {
  it('keeps slash usage hints visible without exposing internal tool names', () => {
    const command: SlashToolCommand = {
      slug: 'connectors-status',
      label: '/connectors-status',
      toolName: 'get_connector_status',
      path: '/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status',
      description: 'Connector status',
      pathParams: ['connector_name'],
      argParams: ['connector_name'],
      usage: '/connectors-status <connector_name>',
    }

    const html = renderToStaticMarkup(<SlashCommandOptionContent command={command} />)

    expect(html).toContain('/connectors-status')
    expect(html).toContain('&lt;connector_name&gt;')
    expect(html).toContain('Connector status')
    expect(html).not.toContain('get_connector_status')
  })
})
