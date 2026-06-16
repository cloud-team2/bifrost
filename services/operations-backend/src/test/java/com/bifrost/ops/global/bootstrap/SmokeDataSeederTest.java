package com.bifrost.ops.global.bootstrap;

import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberId;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmokeDataSeederTest {

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProjectMemberRepository memberRepository;
    @Mock
    private DatasourceRepository datasourceRepository;
    @Mock
    private PipelineRepository pipelineRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void createsFixedUuidSmokeProjectAndSampleRows() {
        String connectorName = ConnectorNaming.sourceConnectorName(SmokeDataSeeder.PIPELINE_ID);
        when(workspaceRepository.findById(SmokeDataSeeder.WORKSPACE_ID)).thenReturn(Optional.empty());
        when(workspaceRepository.existsByName(SmokeDataSeeder.WORKSPACE_NAME)).thenReturn(false);
        when(workspaceRepository.existsByNamespace(SmokeDataSeeder.WORKSPACE_NAMESPACE)).thenReturn(false);
        when(userRepository.findByEmail(SmokeDataSeeder.USER_EMAIL)).thenReturn(Optional.empty());
        when(userRepository.findById(SmokeDataSeeder.USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-smoke-password");
        when(memberRepository.existsById(new ProjectMemberId(
                SmokeDataSeeder.WORKSPACE_ID, SmokeDataSeeder.USER_ID))).thenReturn(false);
        when(datasourceRepository.findById(SmokeDataSeeder.DATASOURCE_ID)).thenReturn(Optional.empty());
        when(datasourceRepository.existsByTenantIdAndName(
                SmokeDataSeeder.WORKSPACE_ID, SmokeDataSeeder.DATASOURCE_NAME)).thenReturn(false);
        when(pipelineRepository.findById(SmokeDataSeeder.PIPELINE_ID)).thenReturn(Optional.empty());
        when(pipelineRepository.existsByTenantIdAndName(
                SmokeDataSeeder.WORKSPACE_ID, SmokeDataSeeder.PIPELINE_NAME)).thenReturn(false);
        when(pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                SmokeDataSeeder.WORKSPACE_ID,
                SmokeDataSeeder.DATASOURCE_ID,
                SmokeDataSeeder.SCHEMA_NAME,
                SmokeDataSeeder.TABLE_NAME,
                PipelinePattern.FAN_OUT)).thenReturn(false);
        when(connectorRepository.findById(SmokeDataSeeder.CONNECTOR_ID)).thenReturn(Optional.empty());
        when(connectorRepository.findByCrName(connectorName)).thenReturn(Optional.empty());
        when(incidentRepository.findById(SmokeDataSeeder.INCIDENT_ID)).thenReturn(Optional.empty());
        when(eventRepository.findById(SmokeDataSeeder.EVENT_ID)).thenReturn(Optional.empty());

        seeder(true).run();

        ArgumentCaptor<WorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceRepository).saveAndFlush(workspaceCaptor.capture());
        assertThat(workspaceCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(workspaceCaptor.getValue().getName()).isEqualTo(SmokeDataSeeder.WORKSPACE_NAME);
        assertThat(workspaceCaptor.getValue().getNamespace()).isEqualTo(SmokeDataSeeder.WORKSPACE_NAMESPACE);
        assertThat(workspaceCaptor.getValue().getOwnerUserId()).isEqualTo(SmokeDataSeeder.USER_ID);
        assertThat(workspaceCaptor.getValue().getStatus()).isEqualTo(WorkspaceEntity.Status.ACTIVE);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.USER_ID);
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(SmokeDataSeeder.USER_EMAIL);
        assertThat(userCaptor.getValue().getName()).isEqualTo(SmokeDataSeeder.USER_NAME);
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded-smoke-password");

        ArgumentCaptor<ProjectMemberEntity> memberCaptor = ArgumentCaptor.forClass(ProjectMemberEntity.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getWorkspaceId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(SmokeDataSeeder.USER_ID);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(Role.OWNER);

        ArgumentCaptor<DatasourceEntity> datasourceCaptor = ArgumentCaptor.forClass(DatasourceEntity.class);
        verify(datasourceRepository).saveAndFlush(datasourceCaptor.capture());
        assertThat(datasourceCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.DATASOURCE_ID);
        assertThat(datasourceCaptor.getValue().getTenantId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(datasourceCaptor.getValue().getDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(datasourceCaptor.getValue().getCdcReadinessStatus()).isEqualTo("OK");
        assertThat(datasourceCaptor.getValue().getConnectionStatus()).isEqualTo("UNREACHABLE");

        ArgumentCaptor<PipelineEntity> pipelineCaptor = ArgumentCaptor.forClass(PipelineEntity.class);
        verify(pipelineRepository).saveAndFlush(pipelineCaptor.capture());
        assertThat(pipelineCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.PIPELINE_ID);
        assertThat(pipelineCaptor.getValue().getTenantId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(pipelineCaptor.getValue().getName()).isEqualTo(SmokeDataSeeder.PIPELINE_NAME);
        assertThat(pipelineCaptor.getValue().getPattern()).isEqualTo(PipelinePattern.FAN_OUT);
        assertThat(pipelineCaptor.getValue().getSourceDatasourceId()).isEqualTo(SmokeDataSeeder.DATASOURCE_ID);
        assertThat(pipelineCaptor.getValue().getSourceConnectorName()).isEqualTo(connectorName);
        assertThat(pipelineCaptor.getValue().getTopicName())
                .isEqualTo("eda.table.smoke-project.smoke_source-11111111.public.orders");
        assertThat(pipelineCaptor.getValue().getStatus()).isEqualTo(PipelineLifecycle.ERROR);

        ArgumentCaptor<ConnectorEntity> connectorCaptor = ArgumentCaptor.forClass(ConnectorEntity.class);
        verify(connectorRepository).saveAndFlush(connectorCaptor.capture());
        assertThat(connectorCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.CONNECTOR_ID);
        assertThat(connectorCaptor.getValue().getPipelineId()).isEqualTo(SmokeDataSeeder.PIPELINE_ID);
        assertThat(connectorCaptor.getValue().getCrName()).isEqualTo(connectorName);
        assertThat(connectorCaptor.getValue().getKind()).isEqualTo(ConnectorKind.SOURCE);
        assertThat(connectorCaptor.getValue().getState()).isEqualTo("FAILED");

        ArgumentCaptor<IncidentEntity> incidentCaptor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).saveAndFlush(incidentCaptor.capture());
        assertThat(incidentCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.INCIDENT_ID);
        assertThat(incidentCaptor.getValue().getTenantId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(incidentCaptor.getValue().getSeverity()).isEqualTo("CRITICAL");
        assertThat(incidentCaptor.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(incidentCaptor.getValue().getSourceId()).isEqualTo(SmokeDataSeeder.PIPELINE_ID);

        ArgumentCaptor<EventEntity> eventCaptor = ArgumentCaptor.forClass(EventEntity.class);
        verify(eventRepository).saveAndFlush(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getId()).isEqualTo(SmokeDataSeeder.EVENT_ID);
        assertThat(eventCaptor.getValue().getTenantId()).isEqualTo(SmokeDataSeeder.WORKSPACE_ID);
        assertThat(eventCaptor.getValue().getPipelineId()).isEqualTo(SmokeDataSeeder.PIPELINE_ID);
        assertThat(eventCaptor.getValue().getIncidentId()).isEqualTo(SmokeDataSeeder.INCIDENT_ID);
        assertThat(eventCaptor.getValue().getLevel()).isEqualTo(EventLevel.ERROR);
    }

    @Test
    void existingFixedUuidRowsAreNoop() {
        WorkspaceEntity workspace = workspace();
        UserEntity user = user();
        DatasourceEntity datasource = datasource();
        PipelineEntity pipeline = pipeline();
        when(workspaceRepository.findById(SmokeDataSeeder.WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(userRepository.findById(SmokeDataSeeder.USER_ID)).thenReturn(Optional.of(user));
        when(memberRepository.existsById(new ProjectMemberId(
                SmokeDataSeeder.WORKSPACE_ID, SmokeDataSeeder.USER_ID))).thenReturn(true);
        when(datasourceRepository.findById(SmokeDataSeeder.DATASOURCE_ID)).thenReturn(Optional.of(datasource));
        when(pipelineRepository.findById(SmokeDataSeeder.PIPELINE_ID)).thenReturn(Optional.of(pipeline));
        when(connectorRepository.findById(SmokeDataSeeder.CONNECTOR_ID)).thenReturn(Optional.of(connector()));
        when(incidentRepository.findById(SmokeDataSeeder.INCIDENT_ID)).thenReturn(Optional.of(incident()));
        when(eventRepository.findById(SmokeDataSeeder.EVENT_ID)).thenReturn(Optional.of(event()));

        seeder(true).run();

        verify(workspaceRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
        verify(memberRepository, never()).saveAndFlush(any());
        verify(datasourceRepository, never()).saveAndFlush(any());
        verify(pipelineRepository, never()).saveAndFlush(any());
        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void disabledSeederDoesNothing() {
        seeder(false).run();

        verifyNoInteractions(
                workspaceRepository,
                userRepository,
                memberRepository,
                datasourceRepository,
                pipelineRepository,
                connectorRepository,
                incidentRepository,
                eventRepository,
                passwordEncoder);
    }

    @Test
    void workspaceNameConflictSkipsWithoutCreatingRows() {
        when(workspaceRepository.findById(SmokeDataSeeder.WORKSPACE_ID)).thenReturn(Optional.empty());
        when(workspaceRepository.existsByName(SmokeDataSeeder.WORKSPACE_NAME)).thenReturn(true);

        seeder(true).run();

        verify(workspaceRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
        verify(memberRepository, never()).saveAndFlush(any());
        verify(datasourceRepository, never()).saveAndFlush(any());
        verify(pipelineRepository, never()).saveAndFlush(any());
        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedWorkspaceIdWithDifferentNamespaceAbortsWithoutCreatingRows() {
        WorkspaceEntity workspace = workspace();
        workspace.setNamespace("real-project");
        when(workspaceRepository.findById(SmokeDataSeeder.WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        seeder(true).run();

        verify(userRepository, never()).findById(SmokeDataSeeder.USER_ID);
        verify(workspaceRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
        verify(memberRepository, never()).saveAndFlush(any());
        verify(datasourceRepository, never()).saveAndFlush(any());
        verify(pipelineRepository, never()).saveAndFlush(any());
        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedDatasourceIdMismatchSkipsPipelineAndChildren() {
        stubExistingWorkspaceUserAndMember();
        DatasourceEntity datasource = datasource();
        datasource.setName("Real Source DB");
        when(datasourceRepository.findById(SmokeDataSeeder.DATASOURCE_ID)).thenReturn(Optional.of(datasource));

        seeder(true).run();

        verify(pipelineRepository, never()).findById(any());
        verify(pipelineRepository, never()).saveAndFlush(any());
        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedPipelineIdMismatchSkipsConnectorIncidentAndEvent() {
        stubExistingWorkspaceUserAndMember();
        when(datasourceRepository.findById(SmokeDataSeeder.DATASOURCE_ID)).thenReturn(Optional.of(datasource()));
        PipelineEntity pipeline = pipeline();
        pipeline.setTopicName("eda.table.real.topic");
        when(pipelineRepository.findById(SmokeDataSeeder.PIPELINE_ID)).thenReturn(Optional.of(pipeline));

        seeder(true).run();

        verify(connectorRepository, never()).findById(any());
        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedConnectorIdMismatchDoesNotOverwriteExistingConnector() {
        stubExistingWorkspaceUserMemberDatasourceAndPipeline();
        ConnectorEntity connector = connector();
        connector.setCrName("real-source");
        when(connectorRepository.findById(SmokeDataSeeder.CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(incidentRepository.findById(SmokeDataSeeder.INCIDENT_ID)).thenReturn(Optional.of(incident()));
        when(eventRepository.findById(SmokeDataSeeder.EVENT_ID)).thenReturn(Optional.of(event()));

        seeder(true).run();

        verify(connectorRepository, never()).saveAndFlush(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedIncidentIdMismatchSkipsEvent() {
        stubExistingWorkspaceUserMemberDatasourceAndPipeline();
        when(connectorRepository.findById(SmokeDataSeeder.CONNECTOR_ID)).thenReturn(Optional.of(connector()));
        IncidentEntity incident = incident();
        incident.setSourceId(UUID.randomUUID());
        when(incidentRepository.findById(SmokeDataSeeder.INCIDENT_ID)).thenReturn(Optional.of(incident));

        seeder(true).run();

        verify(incidentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).findById(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void fixedEventIdMismatchDoesNotOverwriteExistingEvent() {
        stubExistingWorkspaceUserMemberDatasourceAndPipeline();
        when(connectorRepository.findById(SmokeDataSeeder.CONNECTOR_ID)).thenReturn(Optional.of(connector()));
        when(incidentRepository.findById(SmokeDataSeeder.INCIDENT_ID)).thenReturn(Optional.of(incident()));
        EventEntity event = event();
        event.setType("REAL_EVENT");
        when(eventRepository.findById(SmokeDataSeeder.EVENT_ID)).thenReturn(Optional.of(event));

        seeder(true).run();

        verify(eventRepository, never()).saveAndFlush(any());
    }

    private SmokeDataSeeder seeder(boolean enabled) {
        return new SmokeDataSeeder(
                workspaceRepository,
                userRepository,
                memberRepository,
                datasourceRepository,
                pipelineRepository,
                connectorRepository,
                incidentRepository,
                eventRepository,
                passwordEncoder,
                enabled,
                ""
        );
    }

    private void stubExistingWorkspaceUserAndMember() {
        when(workspaceRepository.findById(SmokeDataSeeder.WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(SmokeDataSeeder.USER_ID)).thenReturn(Optional.of(user()));
        when(memberRepository.existsById(new ProjectMemberId(
                SmokeDataSeeder.WORKSPACE_ID, SmokeDataSeeder.USER_ID))).thenReturn(true);
    }

    private void stubExistingWorkspaceUserMemberDatasourceAndPipeline() {
        stubExistingWorkspaceUserAndMember();
        when(datasourceRepository.findById(SmokeDataSeeder.DATASOURCE_ID)).thenReturn(Optional.of(datasource()));
        when(pipelineRepository.findById(SmokeDataSeeder.PIPELINE_ID)).thenReturn(Optional.of(pipeline()));
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(SmokeDataSeeder.WORKSPACE_ID);
        workspace.setName(SmokeDataSeeder.WORKSPACE_NAME);
        workspace.setNamespace(SmokeDataSeeder.WORKSPACE_NAMESPACE);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        workspace.setOwnerUserId(SmokeDataSeeder.USER_ID);
        return workspace;
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(SmokeDataSeeder.USER_ID);
        user.setTenantId(SmokeDataSeeder.WORKSPACE_ID);
        user.setEmail(SmokeDataSeeder.USER_EMAIL);
        user.setName(SmokeDataSeeder.USER_NAME);
        user.setPasswordHash("encoded");
        return user;
    }

    private DatasourceEntity datasource() {
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(SmokeDataSeeder.DATASOURCE_ID);
        datasource.setTenantId(SmokeDataSeeder.WORKSPACE_ID);
        datasource.setName(SmokeDataSeeder.DATASOURCE_NAME);
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost(SmokeDataSeeder.DATASOURCE_HOST);
        datasource.setPort(SmokeDataSeeder.DATASOURCE_PORT);
        datasource.setDbName(SmokeDataSeeder.DATASOURCE_DB_NAME);
        datasource.setUsername(SmokeDataSeeder.DATASOURCE_USERNAME);
        datasource.setSecretRef(SmokeDataSeeder.DATASOURCE_SECRET_REF);
        return datasource;
    }

    private PipelineEntity pipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(SmokeDataSeeder.PIPELINE_ID);
        pipeline.setTenantId(SmokeDataSeeder.WORKSPACE_ID);
        pipeline.setName(SmokeDataSeeder.PIPELINE_NAME);
        pipeline.setSourceDatasourceId(SmokeDataSeeder.DATASOURCE_ID);
        pipeline.setPattern(PipelinePattern.FAN_OUT);
        pipeline.setType(SmokeDataSeeder.PIPELINE_TYPE);
        pipeline.setSchemaName(SmokeDataSeeder.SCHEMA_NAME);
        pipeline.setTableName(SmokeDataSeeder.TABLE_NAME);
        pipeline.setTables(SmokeDataSeeder.PIPELINE_TABLES);
        pipeline.setSourceConnectorName(ConnectorNaming.sourceConnectorName(SmokeDataSeeder.PIPELINE_ID));
        pipeline.setTopicName("eda.table.smoke-project.smoke_source-11111111.public.orders");
        pipeline.setStatus(PipelineLifecycle.ERROR);
        return pipeline;
    }

    private ConnectorEntity connector() {
        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(SmokeDataSeeder.CONNECTOR_ID);
        connector.setPipelineId(SmokeDataSeeder.PIPELINE_ID);
        connector.setCrName(ConnectorNaming.sourceConnectorName(SmokeDataSeeder.PIPELINE_ID));
        connector.setKind(ConnectorKind.SOURCE);
        connector.setConnectorClass(SmokeDataSeeder.CONNECTOR_CLASS);
        connector.setTasksMax(1);
        return connector;
    }

    private IncidentEntity incident() {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(SmokeDataSeeder.INCIDENT_ID);
        incident.setTenantId(SmokeDataSeeder.WORKSPACE_ID);
        incident.setSourceType("PIPELINE");
        incident.setSourceId(SmokeDataSeeder.PIPELINE_ID);
        incident.setGroupingKey(SmokeDataSeeder.INCIDENT_GROUPING_KEY);
        incident.setSeverity(SmokeDataSeeder.INCIDENT_SEVERITY);
        incident.setStatus("OPEN");
        incident.setTitle(SmokeDataSeeder.INCIDENT_TITLE);
        return incident;
    }

    private EventEntity event() {
        EventEntity event = new EventEntity();
        event.setId(SmokeDataSeeder.EVENT_ID);
        event.setTenantId(SmokeDataSeeder.WORKSPACE_ID);
        event.setPipelineId(SmokeDataSeeder.PIPELINE_ID);
        event.setIncidentId(SmokeDataSeeder.INCIDENT_ID);
        event.setLevel(EventLevel.ERROR);
        event.setType(SmokeDataSeeder.EVENT_TYPE);
        event.setMessage(SmokeDataSeeder.EVENT_MESSAGE);
        event.setCategory(SmokeDataSeeder.EVENT_CATEGORY);
        return event;
    }
}
