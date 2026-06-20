package com.bifrost.ops.monitoring.slo;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.monitoring.dto.SliMeasurementResponse;
import com.bifrost.ops.monitoring.sli.UserImpactSliService;
import com.bifrost.ops.monitoring.sli.UserImpactSliStatus;
import com.bifrost.ops.monitoring.sli.UserImpactSliType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SloBurnRateServiceTest {

    private final UserImpactSliService sliService = mock(UserImpactSliService.class);
    private final SloBurnRateService service = new SloBurnRateService(sliService);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void fastBurnRulePagesWhenLongAndShortWindowsExceedThreshold() {
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_FRESHNESS, 60))
                .thenReturn(measured(UserImpactSliType.DATA_FRESHNESS, 0.98, 60));
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_FRESHNESS, 5))
                .thenReturn(measured(UserImpactSliType.DATA_FRESHNESS, 0.98, 5));

        SloBurnRateEvaluation evaluation = service.evaluate(
                workspaceId, UserImpactSliType.DATA_FRESHNESS, 3);

        assertThat(evaluation.route()).isEqualTo(SloAlertRoute.PAGE);
        assertThat(evaluation.severity()).isEqualTo("CRITICAL");
        assertThat(evaluation.ruleName()).isEqualTo("page_fast_burn");
        assertThat(evaluation.longBurnRate()).isGreaterThan(14.4);
        assertThat(evaluation.shortBurnRate()).isGreaterThan(14.4);
        assertThat(evaluation.severityReason())
                .contains("impact=user_sli:data_freshness")
                .contains("urgency=page")
                .contains("affected_resource_count=3");
    }

    @Test
    void slowBurnRuleCreatesTicketWhenPageRulesDoNotFire() {
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_COMPLETENESS, 60))
                .thenReturn(measured(UserImpactSliType.DATA_COMPLETENESS, 0.999, 60));
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_COMPLETENESS, 5))
                .thenReturn(measured(UserImpactSliType.DATA_COMPLETENESS, 0.999, 5));
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_COMPLETENESS, 360))
                .thenReturn(measured(UserImpactSliType.DATA_COMPLETENESS, 0.998, 360));
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_COMPLETENESS, 30))
                .thenReturn(measured(UserImpactSliType.DATA_COMPLETENESS, 0.999, 30));
        when(sliService.measurement(workspaceId, UserImpactSliType.DATA_COMPLETENESS, 4_320))
                .thenReturn(measured(UserImpactSliType.DATA_COMPLETENESS, 0.998, 4_320));

        SloBurnRateEvaluation evaluation = service.evaluate(
                workspaceId, UserImpactSliType.DATA_COMPLETENESS, 1);

        assertThat(evaluation.route()).isEqualTo(SloAlertRoute.TICKET);
        assertThat(evaluation.severity()).isEqualTo("WARNING");
        assertThat(evaluation.ruleName()).isEqualTo("ticket_slow_burn");
    }

    @Test
    void causeOnlyThresholdSignalWithoutUserImpactIsDiagnostic() {
        when(sliService.measurement(org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(unknown(UserImpactSliType.DATA_FRESHNESS, 30));

        SloBurnRateEvaluation evaluation = service.decideIncident(
                workspaceId, EventLevel.ERROR, "CONSUMER_GROUP", "CONSUMER_LAG_CRITICAL", 1);

        assertThat(evaluation.route()).isEqualTo(SloAlertRoute.DIAGNOSTIC);
        assertThat(evaluation.severityReason()).contains("diagnostic signal로 강등");
    }

    private static SliMeasurementResponse measured(UserImpactSliType type, double ratio, int windowMinutes) {
        return SliMeasurementResponse.of(
                type,
                ratio * 100.0,
                100.0,
                ratio,
                UserImpactSliStatus.GOOD,
                windowMinutes,
                Instant.parse("2026-06-20T00:00:00Z"),
                "test",
                null);
    }

    private static SliMeasurementResponse unknown(UserImpactSliType type, int windowMinutes) {
        return SliMeasurementResponse.of(
                type,
                0.0,
                0.0,
                null,
                UserImpactSliStatus.UNKNOWN,
                windowMinutes,
                Instant.parse("2026-06-20T00:00:00Z"),
                "test",
                null);
    }
}
