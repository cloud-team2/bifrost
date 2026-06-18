import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { CommandPalette } from './CommandPalette'
import type { SlashToolCommand } from '../lib/slashCommands'

const cmd = (over: Partial<SlashToolCommand>): SlashToolCommand => ({
  slug: 's',
  label: '/s',
  toolName: 't',
  path: '/p',
  description: 'd',
  pathParams: [],
  argParams: [],
  argEnums: {},
  usage: '/s',
  group: '',
  labelKo: '',
  ...over,
})

const commands = [
  cmd({ toolName: 'list_project_pipelines', group: 'pipeline', labelKo: '파이프라인 목록', description: '파이프라인 목록을 조회합니다.' }),
  cmd({ toolName: 'get_connector_status', group: 'cluster', labelKo: '커넥터 상태', description: '커넥터 상태를 조회합니다.', argParams: ['connector_name'] }),
  cmd({ toolName: 'get_alerts', group: 'incident', labelKo: '인시던트 목록', description: '알림 목록을 조회합니다.' }),
]
const noop = () => {}

describe('CommandPalette', () => {
  it('shows the three domain groups first (no slash commands exposed)', () => {
    const html = renderToStaticMarkup(
      <CommandPalette
        commands={commands}
        loading={false}
        error={null}
        onRunTool={noop}
        onCreatePipeline={noop}
        onClose={noop}
      />,
    )
    expect(html).toContain('기능 그룹을 선택하세요')
    expect(html).toContain('파이프라인')
    expect(html).toContain('클러스터')
    expect(html).toContain('인시던트')
    // 그룹 단계에서는 개별 기능(설명)이 노출되지 않는다.
    expect(html).not.toContain('조회합니다')
  })

  it('renders loading and error states', () => {
    expect(
      renderToStaticMarkup(
        <CommandPalette commands={[]} loading error={null} onRunTool={noop} onCreatePipeline={noop} onClose={noop} />,
      ),
    ).toContain('불러오는 중')
    expect(
      renderToStaticMarkup(
        <CommandPalette commands={[]} loading={false} error="카탈로그 오류" onRunTool={noop} onCreatePipeline={noop} onClose={noop} />,
      ),
    ).toContain('카탈로그 오류')
  })
})
