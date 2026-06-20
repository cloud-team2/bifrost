package com.bifrost.ops.monitoring.slo;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.monitoring.dto.SliMeasurementResponse;
import com.bifrost.ops.monitoring.sli.UserImpactSliService;
import com.bifrost.ops.monitoring.sli.UserImpactSliType;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** SLI 측정값으로 SLO burn-rate와 알림 경로를 계산한다(#892). */
@Service
public class SloBurnRateService {

    private static final String SEV_WARNING = "WARNING";
    private static final String SEV_CRITICAL = "CRITICAL";
    private static final List<UserImpactSliType> INCIDENT_SLIS = List.of(
            UserImpactSliType.DATA_FRESHNESS,
            UserImpactSliType.END_TO_END_LATENCY,
            UserImpactSliType.PROCESSING_SUCCESS_RATE,
            UserImpactSliType.DATA_COMPLETENESS);

    private final UserImpactSliService sliService;

    public SloBurnRateService(UserImpactSliService sliService) {
        this.sliService = sliService;
    }

    public SloBurnRateEvaluation evaluate(UUID workspaceId, int affectedResourceCount) {
        return INCIDENT_SLIS.stream()
                .map(type -> evaluate(workspaceId, type, affectedResourceCount))
                .filter(SloBurnRateEvaluation::userImpactDetected)
                .max(Comparator
                        .comparing((SloBurnRateEvaluation e) -> routeRank(e.route()))
                        .thenComparing(e -> maxBurnRate(e.longBurnRate(), e.shortBurnRate())))
                .orElse(diagnostic("사용자 영향 SLO burn-rate 위반 없음", affectedResourceCount));
    }

    public SloBurnRateEvaluation evaluate(UUID workspaceId,
                                          UserImpactSliType type,
                                          int affectedResourceCount) {
        SloConfig config = SloConfig.defaultFor(type);
        for (SloBurnRateRule rule : config.rules()) {
            Double longBurnRate = burnRate(workspaceId, type, rule.longWindowMinutes(), config.targetRatio());
            Double shortBurnRate = burnRate(workspaceId, type, rule.shortWindowMinutes(), config.targetRatio());
            if (longBurnRate == null || shortBurnRate == null) {
                continue;
            }
            if (longBurnRate >= rule.burnRateThreshold() && shortBurnRate >= rule.burnRateThreshold()) {
                String severity = rule.route() == SloAlertRoute.PAGE ? SEV_CRITICAL : SEV_WARNING;
                return new SloBurnRateEvaluation(
                        type,
                        rule.route(),
                        severity,
                        severityReason(type, rule, longBurnRate, shortBurnRate, affectedResourceCount),
                        rule.name(),
                        longBurnRate,
                        shortBurnRate,
                        config.targetRatio(),
                        affectedResourceCount);
            }
        }
        return diagnostic(type.displayName() + " SLO burn-rate 위반 없음", affectedResourceCount);
    }

    public SloBurnRateEvaluation decideIncident(UUID workspaceId,
                                                EventLevel thresholdLevel,
                                                String sourceType,
                                                String eventType,
                                                int affectedResourceCount) {
        SloBurnRateEvaluation evaluation = evaluate(workspaceId, affectedResourceCount);
        if (evaluation.userImpactDetected()) {
            return evaluation;
        }
        if (isCauseOnlySignal(sourceType, eventType)) {
            return diagnostic("사용자 영향 SLO 위반이 없어 원인 지표를 diagnostic signal로 강등", affectedResourceCount);
        }
        String severity = thresholdLevel == EventLevel.ERROR ? SEV_CRITICAL : SEV_WARNING;
        return new SloBurnRateEvaluation(
                UserImpactSliType.DATA_FRESHNESS,
                thresholdLevel == EventLevel.ERROR ? SloAlertRoute.PAGE : SloAlertRoute.TICKET,
                severity,
                "impact=unknown; urgency=threshold_" + thresholdLevel.name().toLowerCase()
                        + "; slo_burn_rate=unavailable; affected_resource_count=" + Math.max(1, affectedResourceCount),
                "static_threshold_fallback",
                null,
                null,
                UserImpactSliType.DATA_FRESHNESS.targetRatio(),
                Math.max(1, affectedResourceCount));
    }

    private Double burnRate(UUID workspaceId, UserImpactSliType type, int windowMinutes, double targetRatio) {
        SliMeasurementResponse measurement = sliService.measurement(workspaceId, type, windowMinutes);
        if (measurement.sliRatio() == null) {
            return null;
        }
        double budget = 1.0 - targetRatio;
        if (budget <= 0.0) {
            return null;
        }
        return Math.max(0.0, 1.0 - measurement.sliRatio()) / budget;
    }

    private static SloBurnRateEvaluation diagnostic(String reason, int affectedResourceCount) {
        return new SloBurnRateEvaluation(
                UserImpactSliType.DATA_FRESHNESS,
                SloAlertRoute.DIAGNOSTIC,
                SEV_WARNING,
                "impact=none; urgency=diagnostic; slo_burn_rate=below_threshold; affected_resource_count="
                        + Math.max(1, affectedResourceCount) + "; reason=" + reason,
                "diagnostic",
                null,
                null,
                UserImpactSliType.DATA_FRESHNESS.targetRatio(),
                Math.max(1, affectedResourceCount));
    }

    private static String severityReason(UserImpactSliType type,
                                         SloBurnRateRule rule,
                                         double longBurnRate,
                                         double shortBurnRate,
                                         int affectedResourceCount) {
        String urgency = rule.route() == SloAlertRoute.PAGE ? "page" : "ticket";
        return "impact=user_sli:" + type.apiName()
                + "; urgency=" + urgency
                + "; slo_burn_rate_rule=" + rule.name()
                + "; slo_burn_rate_long=" + format(longBurnRate)
                + "; slo_burn_rate_short=" + format(shortBurnRate)
                + "; affected_resource_count=" + Math.max(1, affectedResourceCount);
    }

    private static boolean isCauseOnlySignal(String sourceType, String eventType) {
        String source = sourceType == null ? "" : sourceType.toUpperCase();
        String event = eventType == null ? "" : eventType.toUpperCase();
        return source.equals("CONSUMER_GROUP")
                || source.equals("CONNECTOR")
                || event.contains("CONSUMER_LAG")
                || event.contains("CONNECTOR")
                || event.contains("ERROR_RATE")
                || event.contains("TOPIC_ISR");
    }

    private static int routeRank(SloAlertRoute route) {
        return switch (route) {
            case PAGE -> 3;
            case TICKET -> 2;
            case DIAGNOSTIC -> 1;
        };
    }

    private static double maxBurnRate(Double left, Double right) {
        return Math.max(left == null ? 0.0 : left, right == null ? 0.0 : right);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
