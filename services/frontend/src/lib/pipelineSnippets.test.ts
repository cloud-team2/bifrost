import { describe, it, expect } from 'vitest'
import { buildConsumerSnippets, escapeSnippetValue } from './pipelineSnippets'
import type { ConnectionGuideResponse } from './api'

// ---- escapeSnippetValue -----------------------------------------------

describe('escapeSnippetValue', () => {
  it('escapes backslashes', () => {
    expect(escapeSnippetValue('C:\\path')).toBe('C:\\\\path')
  })
  it('escapes double quotes', () => {
    expect(escapeSnippetValue('say "hi"')).toBe('say \\"hi\\"')
  })
  it('escapes newlines', () => {
    expect(escapeSnippetValue('line1\nline2')).toBe('line1\\nline2')
  })
  it('leaves plain strings unchanged', () => {
    expect(escapeSnippetValue('broker:9092')).toBe('broker:9092')
  })
})

// ---- buildConsumerSnippets --------------------------------------------

const baseGuide: ConnectionGuideResponse = {
  pipelineId: 'pid-1',
  pipelineName: 'orders-eda',
  bootstrapServers: 'broker.example.com:9092',
  recommendedGroupId: 'bifrost-orders-eda-consumer',
  authenticationMethod: 'SASL_SSL',
  credentialReference: {
    namespace: 'platform',
    secretName: 'kafka-sasl-creds',
    keyRefs: {},
    availableKeys: [],
  },
  authenticationTemplates: [],
  topics: [],
}

describe('buildConsumerSnippets — no auth template', () => {
  const snippets = buildConsumerSnippets(baseGuide, 'public.orders', null)

  it('Java snippet contains bootstrap.servers', () => {
    expect(snippets.Java).toContain('broker.example.com:9092')
  })
  it('Java snippet contains group.id', () => {
    expect(snippets.Java).toContain('bifrost-orders-eda-consumer')
  })
  it('Java snippet contains topic name', () => {
    expect(snippets.Java).toContain('public.orders')
  })
  it('Java snippet has no-auth comment when template is null', () => {
    expect(snippets.Java).toContain('// No client authentication properties returned by backend.')
  })
  it('Python snippet contains topic name', () => {
    expect(snippets.Python).toContain('"public.orders"')
  })
  it('Python snippet has no-auth comment', () => {
    expect(snippets.Python).toContain('# No client authentication properties returned by backend.')
  })
  it('Node.js snippet contains bootstrap brokers', () => {
    expect(snippets['Node.js']).toContain('broker.example.com:9092')
  })
  it('Node.js snippet has no-auth comment', () => {
    expect(snippets['Node.js']).toContain('// No client authentication properties returned by backend.')
  })
})

describe('buildConsumerSnippets — with auth template', () => {
  const template: ConnectionGuideResponse['authenticationTemplates'][number] = {
    type: 'SASL_PLAIN',
    securityProtocol: 'SASL_SSL',
    properties: {
      'sasl.mechanism': 'PLAIN',
      'sasl.jaas.config': 'org.apache.kafka.common.security.plain.PlainLoginModule required;',
    },
    credentialReference: baseGuide.credentialReference,
  }
  const snippets = buildConsumerSnippets(baseGuide, 'public.orders', template)

  it('Java snippet includes props.put for each auth property', () => {
    expect(snippets.Java).toContain('props.put("sasl.mechanism", "PLAIN");')
  })
  it('Java snippet escapes auth property values', () => {
    expect(snippets.Java).toContain(
      'props.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required;");',
    )
  })
  it('Python snippet includes commented auth properties', () => {
    expect(snippets.Python).toContain('# sasl.mechanism=PLAIN')
  })
  it('Node.js snippet includes commented auth properties', () => {
    expect(snippets['Node.js']).toContain('// sasl.mechanism=PLAIN')
  })
})

describe('buildConsumerSnippets — distributed trace correlation (traceparent)', () => {
  const snippets = buildConsumerSnippets(baseGuide, 'public.orders', null)

  it('Java snippet reads the traceparent header', () => {
    expect(snippets.Java).toContain('traceparent')
  })
  it('Python snippet reads the traceparent header', () => {
    expect(snippets.Python).toContain('traceparent')
  })
  it('Node.js snippet reads the traceparent header', () => {
    expect(snippets['Node.js']).toContain('traceparent')
  })
})

describe('buildConsumerSnippets — special chars in values are escaped in Java', () => {
  const guideWithSpecialChars: ConnectionGuideResponse = {
    ...baseGuide,
    bootstrapServers: 'broker"evil":9092',
    recommendedGroupId: 'group\\slashed',
  }
  const snippets = buildConsumerSnippets(guideWithSpecialChars, 'public.orders', null)

  it('escapes quotes in bootstrapServers for Java', () => {
    expect(snippets.Java).toContain('broker\\"evil\\":9092')
  })
  it('escapes backslash in groupId for Java', () => {
    expect(snippets.Java).toContain('group\\\\slashed')
  })
})
