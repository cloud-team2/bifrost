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
consumer.subscribe(List.of("${escapeSnippetValue(topicName)}"));

// 소비 + 분산 추적 연결(선택): Debezium이 주입한 W3C traceparent 헤더를 읽어 OTel로 추출하면
// 이 consumer의 처리 span이 source→topic 추적과 같은 traceId로 이어집니다.
while (true) {
  for (var record : consumer.poll(java.time.Duration.ofMillis(500))) {
    var tp = record.headers().lastHeader("traceparent");          // 없으면 null
    var traceparent = tp == null ? null : new String(tp.value());
    // OTel: W3CTraceContextPropagator.getInstance().extract(...)로 traceparent → Context 후 span 시작
    handle(record.value());
  }
}`,
    Python: `# pip install confluent-kafka
from confluent_kafka import Consumer

# Authentication template (${guide.authenticationMethod}) uses Secret ${guide.credentialReference.namespace}/${guide.credentialReference.secretName}.
${commentAuth}
consumer = Consumer({
    "bootstrap.servers": "${escapeSnippetValue(guide.bootstrapServers)}",
    "group.id": "${escapeSnippetValue(guide.recommendedGroupId)}",
    "auto.offset.reset": "earliest",
})
consumer.subscribe(["${escapeSnippetValue(topicName)}"])

try:
    while True:
        msg = consumer.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            print(f"Consumer error: {msg.error()}")
            continue
        # 분산 추적 연결(선택): traceparent 헤더를 OTel로 추출하면 같은 traceId로 이어집니다.
        headers = dict(msg.headers() or [])
        traceparent = headers.get("traceparent", b"").decode() or None
        # OTel: ctx = opentelemetry.propagate.extract({"traceparent": traceparent}) 로 context 복원 후 span 시작
        handle(msg.value())
finally:
    consumer.close()`,
    'Node.js': `const { Kafka } = require("kafkajs")

// Authentication template (${guide.authenticationMethod}) uses Secret ${guide.credentialReference.namespace}/${guide.credentialReference.secretName}.
${jsAuth}
const kafka = new Kafka({ brokers: ["${guide.bootstrapServers}"] })
const consumer = kafka.consumer({ groupId: "${guide.recommendedGroupId}" })
await consumer.subscribe({ topic: "${topicName}" })
await consumer.run({
  eachMessage: async ({ message }) => {
    // 분산 추적 연결(선택): traceparent 헤더를 OTel propagation으로 추출하면 같은 traceId로 이어집니다.
    const traceparent = message.headers?.traceparent?.toString()
    // OTel: propagation.extract(context.active(), { traceparent }) 로 context 복원 후 span 시작
    handle(message.value)
  },
})`,
  }
}
