package com.bifrost.connect.converter;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #425: Postgres {@code timestamptz} 컬럼이 Debezium 기본(io.debezium.time.ZonedTimestamp, ISO 문자열)으로
 * 방출돼 JDBC sink가 varchar로 적재 → 타입 불일치. 이 컨버터는 timestamptz를 Connect Timestamp(논리 타입,
 * epoch-millis Date)로 변환해 sink가 SQL TIMESTAMP로 적재하도록 만든다.
 */
class TimestamptzConverterTest {

    /** registration 콜백을 가로채 등록된 schema/converter를 캡처한다. */
    static final class CapturingRegistration implements CustomConverter.ConverterRegistration<SchemaBuilder> {
        SchemaBuilder schema;
        CustomConverter.Converter converter;
        boolean registered = false;

        @Override
        public void register(SchemaBuilder fieldSchema, CustomConverter.Converter converter) {
            this.schema = fieldSchema;
            this.converter = converter;
            this.registered = true;
        }
    }

    private CapturingRegistration registerFor(String typeName) {
        RelationalColumn column = mock(RelationalColumn.class);
        when(column.typeName()).thenReturn(typeName);
        TimestamptzConverter converter = new TimestamptzConverter();
        converter.configure(new Properties());
        CapturingRegistration reg = new CapturingRegistration();
        converter.converterFor(column, reg);
        return reg;
    }

    @Test
    void timestamptz컬럼은_Connect_Timestamp_논리타입으로_등록된다() {
        CapturingRegistration reg = registerFor("timestamptz");

        assertThat(reg.registered).isTrue();
        Schema built = reg.schema.build();
        assertThat(built.name()).isEqualTo(Timestamp.LOGICAL_NAME);
        assertThat(built.isOptional()).isTrue();
    }

    @Test
    void 대문자_TIMESTAMPTZ도_등록된다() {
        assertThat(registerFor("TIMESTAMPTZ").registered).isTrue();
    }

    @Test
    void timestamptz가_아니면_등록하지_않는다() {
        assertThat(registerFor("int4").registered).isFalse();
        assertThat(registerFor("timestamp").registered).isFalse();
        assertThat(registerFor("varchar").registered).isFalse();
    }

    @Test
    void OffsetDateTime을_epoch_millis_Date로_변환한다() {
        CustomConverter.Converter conv = registerFor("timestamptz").converter;
        OffsetDateTime input = OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(9));

        Object out = conv.convert(input);

        assertThat(out).isInstanceOf(Date.class);
        assertThat(((Date) out).toInstant()).isEqualTo(input.toInstant());
    }

    @Test
    void ISO8601_문자열을_변환한다() {
        CustomConverter.Converter conv = registerFor("timestamptz").converter;

        Object out = conv.convert("2024-01-02T03:04:05.123Z");

        assertThat(out).isInstanceOf(Date.class);
        assertThat(((Date) out).toInstant()).isEqualTo(Instant.parse("2024-01-02T03:04:05.123Z"));
    }

    @Test
    void null은_null로_변환한다() {
        CustomConverter.Converter conv = registerFor("timestamptz").converter;
        assertThat(conv.convert(null)).isNull();
    }
}
