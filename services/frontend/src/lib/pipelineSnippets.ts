import type { ConnectionGuideResponse } from './api'

export function escapeSnippetValue(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')
}

export function buildConsumerSnippets(
  guide: ConnectionGuideResponse,
  topicName: string,
  template: ConnectionGuideResponse['authenticationTemplates'][number] | null,
): Record<'Java' | 'Python' | 'Node.js', string> {
  const javaAuth = template
    ? Object.entries(template.properties)
        .map(([key, value]) => `props.put("${escapeSnippetValue(key)}", "${escapeSnippetValue(value)}");`)
        .join('\n')
    : '// No client authentication properties returned by backend.'
  const commentAuth = template
    ? Object.entries(template.properties)
        .map(([key, value]) => `# ${key}=${value}`)
        .join('\n')
    : '# No client authentication properties returned by backend.'
  const jsAuth = template
    ? Object.entries(template.properties)
        .map(([key, value]) => `// ${key}=${value}`)
        .join('\n')
    : '// No client authentication properties returned by backend.'

  return {
    Java: `var props = new Properties();
props.put("bootstrap.servers", "${escapeSnippetValue(guide.bootstrapServers)}");
props.put("group.id", "${escapeSnippetValue(guide.recommendedGroupId)}");
${javaAuth}
props.put("key.deserializer", StringDeserializer.class);
props.put("value.deserializer", StringDeserializer.class);

var consumer = new KafkaConsumer<>(props);
consumer.subscribe(List.of("${escapeSnippetValue(topicName)}"));`,
    Python: `from kafka import KafkaConsumer

# Authentication template (${guide.authenticationMethod}) uses Secret ${guide.credentialReference.namespace}/${guide.credentialReference.secretName}.
${commentAuth}
consumer = KafkaConsumer(
    "${topicName}",
    bootstrap_servers="${guide.bootstrapServers}",
    group_id="${guide.recommendedGroupId}",
)
for msg in consumer:
    handle(msg.value)`,
    'Node.js': `const { Kafka } = require("kafkajs")

// Authentication template (${guide.authenticationMethod}) uses Secret ${guide.credentialReference.namespace}/${guide.credentialReference.secretName}.
${jsAuth}
const kafka = new Kafka({ brokers: ["${guide.bootstrapServers}"] })
const consumer = kafka.consumer({ groupId: "${guide.recommendedGroupId}" })
await consumer.subscribe({ topic: "${topicName}" })`,
  }
}
