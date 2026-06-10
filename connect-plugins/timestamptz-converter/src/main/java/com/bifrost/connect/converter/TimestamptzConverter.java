package com.bifrost.connect.converter;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Timestamp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Properties;

/**
 * Debezium 커스텀 컨버터(#425): Postgres {@code timestamptz}(TIMESTAMP WITH TIME ZONE) 컬럼을
 * Kafka Connect {@code Timestamp} 논리 타입(epoch-millis {@link Date})으로 변환한다.
 *
 * <p><b>배경.</b> Debezium은 {@code time.precision.mode}와 무관하게 timestamptz를 항상
 * {@code io.debezium.time.ZonedTimestamp}(ISO-8601 <i>문자열</i>)로 방출한다. Confluent JDBC sink는
 * 이 문자열을 그대로 varchar로 적재하므로, 대상 컬럼이 {@code timestamp with time zone}이면
 * "column ... is of type timestamp with time zone but expression is of type character varying"로
 * INSERT가 실패한다(#425, Confluent JDBC #921). 이 컨버터로 source 단계에서 Connect Timestamp(논리 타입)로
 * 바꿔주면 sink가 SQL TIMESTAMP로 자연스럽게 적재한다.
 *
 * <p><b>등록.</b> source KafkaConnector config에 아래처럼 등록한다(ops-backend SourceDebeziumConnectorMapper):
 * <pre>
 *   converters: timestamptz
 *   converters.timestamptz.type: com.bifrost.connect.converter.TimestamptzConverter
 * </pre>
 *
 * <p><b>정밀도.</b> Connect Timestamp는 millisecond 해상도다. timestamptz의 microsecond 이하는
 * 절삭된다(instant 자체는 보존). ms 정밀도면 충분한 운영 데이터를 전제로 한다.
 */
public class TimestamptzConverter implements CustomConverter<SchemaBuilder, RelationalColumn> {

    @Override
    public void configure(Properties props) {
        // 설정 없음. (정밀도/타깃 타입 옵션이 필요해지면 여기서 props로 받는다.)
    }

    @Override
    public void converterFor(RelationalColumn column, ConverterRegistration<SchemaBuilder> registration) {
        String typeName = column.typeName();
        if (typeName == null || !"timestamptz".equalsIgnoreCase(typeName)) {
            return;
        }
        // Connect Timestamp 논리 타입(epoch-millis Date). optional: nullable 컬럼 대응.
        registration.register(Timestamp.builder().optional(), this::convert);
    }

    /** timestamptz 원시 값을 epoch-millis {@link Date}로 변환한다. null은 null로 통과시킨다. */
    Object convert(Object value) {
        Instant instant = toInstant(value);
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        if (value instanceof CharSequence text) {
            return parseString(text.toString());
        }
        return null;
    }

    /** Debezium ZonedTimestamp 문자열(offset 포함 ISO-8601, 예: {@code 2024-01-02T03:04:05.123456Z}) 파싱. */
    private Instant parseString(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException offsetMiss) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException instantMiss) {
                return null;
            }
        }
    }
}
