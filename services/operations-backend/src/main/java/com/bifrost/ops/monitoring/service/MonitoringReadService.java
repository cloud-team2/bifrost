package com.bifrost.ops.monitoring.service;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.database.health.DatabaseHealthProbeJob;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.monitoring.dto.OverviewResponse;
import com.bifrost.ops.monitoring.dto.ResourceEventResponse;
import com.bifrost.ops.pipeline.ConnectorRuntimeState;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListPartitionReassignmentsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 모니터링 read API 서비스 레이어(S5). */
@Service
public class MonitoringReadService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringReadService.class);

    private final PipelineRepository pipelineRepository;
    private final DatasourceRepository datasourceRepository;
    private final IncidentRepository incidentRepository;
    private final ConnectorRepository connectorRepository;
    private final AdminClient adminClient;

    public MonitoringReadService(PipelineRepository pipelineRepository,
                                  DatasourceRepository datasourceRepository,
                                  IncidentRepository incidentRepository,
                                  ConnectorRepository connectorRepository,
                                  AdminClient adminClient) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.incidentRepository = incidentRepository;
        this.connectorRepository = connectorRepository;
        this.adminClient = adminClient;
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview(UUID tenantId) {
        long total = pipelineRepository.countByTenantId(tenantId);
        long running = pipelineRepository.countByTenantIdAndStatus(tenantId, PipelineLifecycle.ACTIVE);
        long error = pipelineRepository.countByTenantIdAndStatus(tenantId, PipelineLifecycle.ERROR);

        List<DatasourceEntity> datasources = datasourceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        long healthy = datasources.stream()
                .filter(d -> DatabaseHealthProbeJob.HEALTHY.equals(d.getConnectionStatus()))
                .count();
        long unreachable = datasources.stream()
                .filter(d -> DatabaseHealthProbeJob.UNREACHABLE.equals(d.getConnectionStatus()))
                .count();
        long openIncidents = incidentRepository.countByTenantIdAndStatus(tenantId, "OPEN");
        long totalConnectors = connectorRepository.countByTenantId(tenantId);
        long failedConnectors = connectorRepository.countByTenantIdAndStateIn(
                tenantId,
                ConnectorRuntimeState.FAILED.name(),
                ConnectorRuntimeState.PARTIALLY_FAILED.name());

        return new OverviewResponse(total, running, error, healthy, unreachable,
                openIncidents,
                totalConnectors,
                failedConnectors);
    }

    public List<ResourceEventResponse> resourceEvents(UUID tenantId) {
        List<ResourceEventResponse> events = new ArrayList<>();
        try {
            // KRaft election 진행 중인 파티션 조회
            ListPartitionReassignmentsResult result = adminClient.listPartitionReassignments();
            result.reassignments().get(5L, TimeUnit.SECONDS).forEach((tp, reassignment) ->
                    events.add(new ResourceEventResponse(
                            "PARTITION_REASSIGNMENT",
                            tp.topic() + "-" + tp.partition(),
                            "replicas=" + reassignment.replicas() + " addingReplicas=" + reassignment.addingReplicas(),
                            Instant.now())));
        } catch (Exception e) {
            log.debug("resource-events 조회 실패(무시): {}", e.getMessage());
        }
        return events;
    }
}
