package com.bifrost.ops.pipeline.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMessageServiceTest {

    @Test
    void serializedSizeBytesAddsKnownKeyAndValueSizes() {
        assertThat(PipelineMessageService.serializedSizeBytes(10, 25)).isEqualTo(35L);
    }

    @Test
    void serializedSizeBytesIgnoresUnknownKeyOrValueSizeWhenOtherSideIsKnown() {
        assertThat(PipelineMessageService.serializedSizeBytes(-1, 25)).isEqualTo(25L);
        assertThat(PipelineMessageService.serializedSizeBytes(10, -1)).isEqualTo(10L);
    }

    @Test
    void serializedSizeBytesPreservesUnknownWhenKafkaReportsNoSizes() {
        assertThat(PipelineMessageService.serializedSizeBytes(-1, -1)).isNull();
    }
}
