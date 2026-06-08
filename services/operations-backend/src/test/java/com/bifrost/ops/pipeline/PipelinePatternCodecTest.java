package com.bifrost.ops.pipeline;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelinePatternCodecTest {

    @Test
    void parsesFrontendAndBackendForms() {
        assertThat(PipelinePatternCodec.parse("fan-out")).isEqualTo(PipelinePattern.FAN_OUT);
        assertThat(PipelinePatternCodec.parse("FAN_OUT")).isEqualTo(PipelinePattern.FAN_OUT);
        assertThat(PipelinePatternCodec.parse("direct")).isEqualTo(PipelinePattern.DIRECT);
        assertThat(PipelinePatternCodec.parse("DIRECT")).isEqualTo(PipelinePattern.DIRECT);
    }

    @Test
    void rejectsUnknownPattern() {
        assertThatThrownBy(() -> PipelinePatternCodec.parse("oracle"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PipelinePatternCodec.parse(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapsBackToApiForm() {
        assertThat(PipelinePatternCodec.toApi(PipelinePattern.FAN_OUT)).isEqualTo("fan-out");
        assertThat(PipelinePatternCodec.toApi(PipelinePattern.DIRECT)).isEqualTo("direct");
    }
}
