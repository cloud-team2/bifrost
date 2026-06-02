package com.bifrost.ops.provisioning.watcher;

import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
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
        PipelineLifecycle lifecycle = toLifecycle(synthetic);
        String lastError = firstFailedTrace(connectorState, tasks);

        return new ConnectorStatusUpdate(name, synthetic, lifecycle, total, failed, lastError);
    }

    private ConnectorRuntimeState synthesize(String connectorState, int total, int failed) {
        if (connectorState == null) {
            return ConnectorRuntimeState.UNKNOWN;
        }
        return switch (connectorState.toUpperCase()) {
            case "FAILED" -> ConnectorRuntimeState.FAILED;
            case "PAUSED" -> ConnectorRuntimeState.PAUSED;
            case "UNASSIGNED" -> ConnectorRuntimeState.UNASSIGNED;
            case "RUNNING" -> failed > 0
                    ? ConnectorRuntimeState.PARTIALLY_FAILED
                    : ConnectorRuntimeState.RUNNING;
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
        // 비밀값/장문 stacktrace가 그대로 흐르지 않도록 길이 제한
        return s.length() <= LAST_ERROR_MAX ? s : s.substring(0, LAST_ERROR_MAX) + "...";
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
