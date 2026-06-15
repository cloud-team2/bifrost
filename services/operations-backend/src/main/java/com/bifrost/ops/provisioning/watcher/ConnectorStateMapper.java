package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * KafkaConnector CR 상태 → (합성 connector 상태, pipeline 상태) 매핑의 단일 출처(#13).
 *
 * <p>설계 §6 / 부록 B.2 매핑 규칙:
 * <table>
 *   <tr><th>connector + task</th><th>connector 상태</th><th>pipeline 상태</th></tr>
 *   <tr><td>RUNNING, 모든 task RUNNING</td><td>RUNNING</td><td>ACTIVE</td></tr>
 *   <tr><td>RUNNING, 일부 task FAILED</td><td>PARTIALLY_FAILED</td><td>LAG</td></tr>
 *   <tr><td>FAILED</td><td>FAILED</td><td>ERROR</td></tr>
 *   <tr><td>PAUSED</td><td>PAUSED</td><td>PAUSED</td></tr>
 *   <tr><td>UNASSIGNED / 상태 미확정</td><td>UNASSIGNED/UNKNOWN</td><td>CREATING</td></tr>
 * </table>
 *
 * <p>lag 임계(consumer lag 5,000/50,000) 초과 판정은 watch가 아니라 별도 metric으로 하므로
 * 여기서는 connector/task 상태만으로 ACTIVE/LAG/ERROR/PAUSED/CREATING을 산출한다.
 */
@Component
public class ConnectorStateMapper {

    private static final int LAST_ERROR_MAX = 500;

    /** KafkaConnector CR을 {@link ConnectorStatusUpdate}로 매핑한다. */
    public ConnectorStatusUpdate map(KafkaConnector cr) {
        String name = cr.getMetadata() != null ? cr.getMetadata().getName() : null;
        String connectorState = readConnectorState(cr);
        List<Map<String, Object>> tasks = readTasks(cr);

        int total = tasks.size();
        int failed = (int) tasks.stream()
                .map(t -> String.valueOf(t.get("state")))
                .filter("FAILED"::equalsIgnoreCase)
                .count();

        ConnectorRuntimeState synthetic = synthesize(connectorState, total, failed);
        String lastError = firstFailedTrace(connectorState, tasks);

        // CR이 NotReady(operator가 커넥터 배포 거부: config invalid·DB 연결 거부 등)면,
        // runtime connectorStatus가 비어 UNKNOWN/UNASSIGNED로 보여도 '아직 시작 중'이 아니라 '실패'다(#155).
        // 이미 RUNNING/FAILED/PAUSED로 판정됐으면 runtime 상태를 우선한다.
        if (synthetic == ConnectorRuntimeState.UNKNOWN || synthetic == ConnectorRuntimeState.UNASSIGNED) {
            String notReady = notReadyMessage(cr);
            if (notReady != null) {
                synthetic = ConnectorRuntimeState.FAILED;
                lastError = truncate(notReady);
            }
        }

        PipelineLifecycle lifecycle = toLifecycle(synthetic);
        return new ConnectorStatusUpdate(name, synthetic, lifecycle, total, failed, lastError);
    }

    /**
     * KafkaConnector CR의 conditions에서 NotReady(=배포 실패) 메시지를 찾는다. 없으면 null.
     * Strimzi는 커넥터를 생성 못 하면 {@code NotReady=True}(또는 {@code Ready=False})로 사유를 남긴다.
     */
    private String notReadyMessage(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConditions() == null) {
            return null;
        }
        for (Condition c : status.getConditions()) {
            boolean notReady =
                    ("NotReady".equalsIgnoreCase(c.getType()) && "True".equalsIgnoreCase(c.getStatus()))
                            || ("Ready".equalsIgnoreCase(c.getType()) && "False".equalsIgnoreCase(c.getStatus()));
            if (notReady) {
                String msg = c.getMessage() != null ? c.getMessage() : c.getReason();
                return msg != null ? msg : "connector NotReady";
            }
        }
        return null;
    }

    private ConnectorRuntimeState synthesize(String connectorState, int total, int failed) {
        if (connectorState == null) {
            return ConnectorRuntimeState.UNKNOWN;
        }
        return switch (connectorState.toUpperCase()) {
            case "FAILED" -> ConnectorRuntimeState.FAILED;
            case "PAUSED" -> ConnectorRuntimeState.PAUSED;
            case "UNASSIGNED" -> ConnectorRuntimeState.UNASSIGNED;
            // 전체 task 실패면 connector는 RUNNING이어도 사실상 down → FAILED(→ERROR).
            // 일부만 실패면 PARTIALLY_FAILED(→LAG). (#179: DB 장애 등으로 모든 task가 죽는 경우 error로)
            case "RUNNING" -> {
                if (failed <= 0) yield ConnectorRuntimeState.RUNNING;
                else if (total > 0 && failed >= total) yield ConnectorRuntimeState.FAILED;
                else yield ConnectorRuntimeState.PARTIALLY_FAILED;
            }
            default -> ConnectorRuntimeState.UNKNOWN;
        };
    }

    private PipelineLifecycle toLifecycle(ConnectorRuntimeState state) {
        return switch (state) {
            case RUNNING -> PipelineLifecycle.ACTIVE;
            case PARTIALLY_FAILED -> PipelineLifecycle.LAG;
            case FAILED -> PipelineLifecycle.ERROR;
            case PAUSED -> PipelineLifecycle.PAUSED;
            case UNASSIGNED, UNKNOWN -> PipelineLifecycle.CREATING;
        };
    }

    private String firstFailedTrace(String connectorState, List<Map<String, Object>> tasks) {
        // connector 레벨 FAILED면 connector trace 우선, 아니면 첫 FAILED task trace
        for (Map<String, Object> t : tasks) {
            if ("FAILED".equalsIgnoreCase(String.valueOf(t.get("state")))) {
                Object trace = t.get("trace");
                if (trace != null) {
                    return truncate(trace.toString());
                }
            }
        }
        return null;
    }

    private String truncate(String s) {
        // 비밀값/장문 stacktrace가 그대로 흐르지 않도록 길이 제한.
        if (s.length() <= LAST_ERROR_MAX) {
            return s;
        }
        // (#692) 단순 head-cut은 root cause('Caused by: ...')를 버린다. 그 결과 사용자 메시지가
        // 'Exiting WorkerSinkTask' 같은 상단 일반 예외만 남고, DB 연결 실패 dedup 판별
        // (ConnectorErrorMessages.isDbConnectionFailure)도 실패한다. head(상단 예외)와 가장 깊은
        // 'Caused by:' 라인(실제 원인)을 함께 보존한다.
        String rootCause = deepestCauseLine(s);
        if (rootCause != null && rootCause.length() < LAST_ERROR_MAX) {
            int headBudget = Math.max(0, LAST_ERROR_MAX - rootCause.length() - 5);
            return s.substring(0, headBudget) + " ... " + rootCause;
        }
        return s.substring(0, LAST_ERROR_MAX) + "...";
    }

    /** 스택트레이스에서 가장 깊은(=root) 'Caused by:' 한 줄을 추출한다. 없으면 null. */
    private static String deepestCauseLine(String s) {
        int idx = s.lastIndexOf("Caused by:");
        if (idx < 0) {
            return null;
        }
        int end = s.indexOf('\n', idx);
        return (end < 0 ? s.substring(idx) : s.substring(idx, end)).strip();
    }

    private String readConnectorState(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConnectorStatus() == null) {
            return null;
        }
        Object connector = status.getConnectorStatus().get("connector");
        if (connector instanceof Map<?, ?> connectorMap) {
            Object state = connectorMap.get("state");
            return state != null ? state.toString() : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTasks(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConnectorStatus() == null) {
            return List.of();
        }
        Object tasks = status.getConnectorStatus().get("tasks");
        if (tasks instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Map)
                    .map(o -> (Map<String, Object>) o)
                    .toList();
        }
        return List.of();
    }
}
