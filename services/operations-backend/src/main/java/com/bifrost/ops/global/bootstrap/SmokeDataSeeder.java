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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 라이브 read-tool 스모크 검증용 프로젝트 seed(#650).
 *
 * <p>운영 DB를 수동으로 고치는 대신 앱 부트스트랩에서 고정 UUID 프로젝트를 멱등 보장한다.
 * 이미 존재하는 데이터는 update/delete하지 않고, seed-owned 행이 없을 때만 추가한다.
 */
@Component
public class SmokeDataSeeder implements CommandLineRunner {

    static final UUID WORKSPACE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555556");
    static final UUID DATASOURCE_ID = UUID.fromString("11111111-2222-3333-4444-555555555557");
    static final UUID PIPELINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555558");
    static final UUID CONNECTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555559");
    static final UUID INCIDENT_ID = UUID.fromString("11111111-2222-3333-4444-55555555555a");
    static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-55555555555b");

    static final String WORKSPACE_NAME = "Bifrost Smoke Project";
    static final String WORKSPACE_NAMESPACE = "smoke-project";
    static final String USER_EMAIL = "smoke@bifrost.io";
    static final String USER_NAME = "Bifrost Smoke";
    static final String DATASOURCE_NAME = "Smoke Source DB";
    static final String DATASOURCE_HOST = "smoke-postgres.invalid";
    static final int DATASOURCE_PORT = 5432;
    static final String DATASOURCE_DB_NAME = "smoke_source";
    static final String DATASOURCE_USERNAME = "smoke_reader";
    static final String DATASOURCE_SECRET_REF = "smoke-seed/postgres";
    static final String PIPELINE_NAME = "Smoke Orders EDA";
    static final String PIPELINE_TYPE = "CDC";
    static final String SCHEMA_NAME = "public";
    static final String TABLE_NAME = "orders";
    static final String PIPELINE_TABLES = "[\"public.orders\"]";
    static final String CONNECTOR_CLASS = "io.debezium.connector.postgresql.PostgresConnector";
    static final String INCIDENT_GROUPING_KEY = "smoke:connector:" + PIPELINE_ID;
    static final String INCIDENT_SEVERITY = "CRITICAL";
    static final String INCIDENT_TITLE = "Smoke connector placeholder failure";
    static final String EVENT_TYPE = "SMOKE_CONNECTOR_FAILURE";
    static final String EVENT_MESSAGE = "Seeded smoke event for project-scoped read-tool validation.";
    static final String EVENT_CATEGORY = "smoke";

    private static final Logger log = LoggerFactory.getLogger(SmokeDataSeeder.class);

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final DatasourceRepository datasourceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final IncidentRepository incidentRepository;
    private final EventRepository eventRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final String userPassword;

    public SmokeDataSeeder(WorkspaceRepository workspaceRepository,
                           UserRepository userRepository,
                           ProjectMemberRepository memberRepository,
                           DatasourceRepository datasourceRepository,
                           PipelineRepository pipelineRepository,
                           ConnectorRepository connectorRepository,
                           IncidentRepository incidentRepository,
                           EventRepository eventRepository,
                           PasswordEncoder passwordEncoder,
                           PlatformTransactionManager transactionManager,
                           @Value("${smoke.seed.enabled:true}") boolean enabled,
                           @Value("${smoke.seed.user-password:}") String userPassword) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.datasourceRepository = datasourceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.userPassword = userPassword;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> seed());
        } catch (RuntimeException ex) {
            log.warn("[smoke-seed] seed failed; continuing application startup ({})", ex.getClass().getName());
        }
    }

    private void seed() {
        Optional<WorkspaceEntity> workspace = ensureWorkspace();
        if (workspace.isEmpty()) {
            return;
        }
        Optional<UserEntity> user = ensureUser();
        user.ifPresent(this::ensureOwnerMembership);

        Optional<DatasourceEntity> datasource = ensureDatasource(WORKSPACE_ID);
        Optional<PipelineEntity> pipeline = datasource.flatMap(this::ensurePipeline);
        pipeline.ifPresent(seedPipeline -> {
            ensureConnector(seedPipeline);
            if (ensureIncident(seedPipeline)) {
                ensureEvent(seedPipeline);
            }
        });
    }

    private Optional<WorkspaceEntity> ensureWorkspace() {
        Optional<WorkspaceEntity> existing = workspaceRepository.findById(WORKSPACE_ID);
        if (existing.isPresent()) {
            if (!isSmokeWorkspace(existing.get())) {
                log.warn("[smoke-seed] fixed workspace id exists but is not the smoke project — seed aborted ({})",
                        WORKSPACE_ID);
                return Optional.empty();
            }
            log.info("[smoke-seed] smoke project already exists — skip workspace ({})", WORKSPACE_ID);
            return existing;
        }
        if (workspaceRepository.existsByName(WORKSPACE_NAME)
                || workspaceRepository.existsByNamespace(WORKSPACE_NAMESPACE)
                || ownerEmailConflicts()) {
            log.warn("[smoke-seed] smoke project seed skipped due to existing name/namespace/user conflict");
            return Optional.empty();
        }

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(WORKSPACE_ID);
        workspace.setName(WORKSPACE_NAME);
        workspace.setNamespace(WORKSPACE_NAMESPACE);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        workspace.setOwnerUserId(USER_ID);
        workspaceRepository.saveAndFlush(workspace);
        log.info("[smoke-seed] smoke project created: {} ({})", WORKSPACE_NAME, WORKSPACE_ID);
        return Optional.of(workspace);
    }

    private boolean isSmokeWorkspace(WorkspaceEntity workspace) {
        return WORKSPACE_NAME.equals(workspace.getName())
                && WORKSPACE_NAMESPACE.equals(workspace.getNamespace())
                && WorkspaceEntity.Status.ACTIVE == workspace.getStatus()
                && USER_ID.equals(workspace.getOwnerUserId());
    }

    private boolean ownerEmailConflicts() {
        return userRepository.findByEmail(USER_EMAIL)
                .map(user -> !USER_ID.equals(user.getId()))
                .orElse(false);
    }

    private Optional<UserEntity> ensureUser() {
        Optional<UserEntity> existing = userRepository.findById(USER_ID);
        if (existing.isPresent()) {
            UserEntity user = existing.get();
            if (!isSmokeUser(user)) {
                log.warn("[smoke-seed] fixed smoke user id exists but is not the smoke owner — membership skipped ({})",
                        USER_ID);
                return Optional.empty();
            }
            return existing;
        }
        if (ownerEmailConflicts()) {
            log.warn("[smoke-seed] smoke user email belongs to another user — membership skipped ({})", USER_EMAIL);
            return Optional.empty();
        }

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setTenantId(WORKSPACE_ID);
        user.setEmail(USER_EMAIL);
        user.setName(USER_NAME);
        user.setPasswordHash(passwordEncoder.encode(resolvePassword()));
        userRepository.saveAndFlush(user);
        log.info("[smoke-seed] smoke owner user created: {}", USER_EMAIL);
        return Optional.of(user);
    }

    private boolean isSmokeUser(UserEntity user) {
        return WORKSPACE_ID.equals(user.getTenantId())
                && USER_EMAIL.equals(user.getEmail())
                && USER_NAME.equals(user.getName());
    }

    private String resolvePassword() {
        if (userPassword == null || userPassword.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return userPassword;
    }

    private void ensureOwnerMembership(UserEntity user) {
        ProjectMemberId memberId = new ProjectMemberId(WORKSPACE_ID, user.getId());
        if (memberRepository.existsById(memberId)) {
            return;
        }
        memberRepository.saveAndFlush(new ProjectMemberEntity(WORKSPACE_ID, user.getId(), Role.OWNER));
        log.info("[smoke-seed] smoke owner membership created: {} / {}", WORKSPACE_ID, user.getId());
    }

    private Optional<DatasourceEntity> ensureDatasource(UUID tenantId) {
        Optional<DatasourceEntity> existing = datasourceRepository.findById(DATASOURCE_ID);
        if (existing.isPresent()) {
            DatasourceEntity datasource = existing.get();
            if (!isSmokeDatasource(datasource, tenantId)) {
                log.warn("[smoke-seed] fixed datasource id exists but is not the smoke datasource — pipeline seed skipped ({})",
                        DATASOURCE_ID);
                return Optional.empty();
            }
            return existing;
        }
        if (datasourceRepository.existsByTenantIdAndName(tenantId, DATASOURCE_NAME)) {
            log.warn("[smoke-seed] datasource name conflict — pipeline seed skipped ({})", DATASOURCE_NAME);
            return Optional.empty();
        }

        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(DATASOURCE_ID);
        datasource.setTenantId(tenantId);
        datasource.setName(DATASOURCE_NAME);
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost(DATASOURCE_HOST);
        datasource.setPort(DATASOURCE_PORT);
        datasource.setDbName(DATASOURCE_DB_NAME);
        datasource.setUsername(DATASOURCE_USERNAME);
        datasource.setSecretRef(DATASOURCE_SECRET_REF);
        datasource.setCdcReadinessStatus("OK");
        datasource.setCdcReadinessReport("{\"seed\":\"smoke\"}");
        datasource.setConnectionStatus("UNREACHABLE");
        datasource.setConnectionError("seed placeholder datasource");
        datasource.setConnectionCheckedAt(Instant.now());
        datasourceRepository.saveAndFlush(datasource);
        log.info("[smoke-seed] smoke datasource created: {}", DATASOURCE_ID);
        return Optional.of(datasource);
    }

    private boolean isSmokeDatasource(DatasourceEntity datasource, UUID tenantId) {
        return tenantId.equals(datasource.getTenantId())
                && DATASOURCE_NAME.equals(datasource.getName())
                && DbType.POSTGRESQL == datasource.getDbType()
                && DATASOURCE_HOST.equals(datasource.getHost())
                && DATASOURCE_PORT == datasource.getPort()
                && DATASOURCE_DB_NAME.equals(datasource.getDbName())
                && DATASOURCE_USERNAME.equals(datasource.getUsername())
                && DATASOURCE_SECRET_REF.equals(datasource.getSecretRef());
    }

    private Optional<PipelineEntity> ensurePipeline(DatasourceEntity datasource) {
        Optional<PipelineEntity> existing = pipelineRepository.findById(PIPELINE_ID);
        if (existing.isPresent()) {
            PipelineEntity pipeline = existing.get();
            if (!isSmokePipeline(pipeline)) {
                log.warn("[smoke-seed] fixed pipeline id exists but is not the smoke pipeline — connector/event seed skipped ({})",
                        PIPELINE_ID);
                return Optional.empty();
            }
            return existing;
        }
        if (pipelineRepository.existsByTenantIdAndName(WORKSPACE_ID, PIPELINE_NAME)
                || pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                        WORKSPACE_ID, DATASOURCE_ID, SCHEMA_NAME, TABLE_NAME, PipelinePattern.FAN_OUT)) {
            log.warn("[smoke-seed] pipeline unique conflict — connector/event seed skipped ({})", PIPELINE_NAME);
            return Optional.empty();
        }

        String connectorName = ConnectorNaming.sourceConnectorName(PIPELINE_ID);
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(PIPELINE_ID);
        pipeline.setTenantId(WORKSPACE_ID);
        pipeline.setName(PIPELINE_NAME);
        pipeline.setPattern(PipelinePattern.FAN_OUT);
        pipeline.setType(PIPELINE_TYPE);
        pipeline.setSourceDatasourceId(datasource.getId());
        pipeline.setSchemaName(SCHEMA_NAME);
        pipeline.setTableName(TABLE_NAME);
        pipeline.setTables(PIPELINE_TABLES);
        pipeline.setSourceConnectorName(connectorName);
        pipeline.setTopicName(expectedTopicName());
        pipeline.setStatus(PipelineLifecycle.ERROR);
        pipeline.setStatusMessage("Smoke seed placeholder pipeline");
        pipeline.setStatusUpdatedAt(Instant.now());
        pipelineRepository.saveAndFlush(pipeline);
        log.info("[smoke-seed] smoke pipeline created: {}", PIPELINE_ID);
        return Optional.of(pipeline);
    }

    private boolean isSmokePipeline(PipelineEntity pipeline) {
        String connectorName = ConnectorNaming.sourceConnectorName(PIPELINE_ID);
        return WORKSPACE_ID.equals(pipeline.getTenantId())
                && PIPELINE_NAME.equals(pipeline.getName())
                && PIPELINE_TYPE.equals(pipeline.getType())
                && DATASOURCE_ID.equals(pipeline.getSourceDatasourceId())
                && SCHEMA_NAME.equals(pipeline.getSchemaName())
                && TABLE_NAME.equals(pipeline.getTableName())
                && PIPELINE_TABLES.equals(pipeline.getTables())
                && PipelinePattern.FAN_OUT == pipeline.getPattern()
                && connectorName.equals(pipeline.getSourceConnectorName())
                && expectedTopicName().equals(pipeline.getTopicName());
    }

    private String expectedTopicName() {
        return ConnectorNaming.topicName(
                PipelinePattern.FAN_OUT,
                WORKSPACE_NAMESPACE,
                DATASOURCE_DB_NAME,
                DATASOURCE_ID,
                SCHEMA_NAME,
                TABLE_NAME);
    }

    private void ensureConnector(PipelineEntity pipeline) {
        Optional<ConnectorEntity> existing = connectorRepository.findById(CONNECTOR_ID);
        if (existing.isPresent()) {
            if (!isSmokeConnector(existing.get(), pipeline)) {
                log.warn("[smoke-seed] fixed connector id exists but is not the smoke connector — skip ({})",
                        CONNECTOR_ID);
            }
            return;
        }
        String connectorName = ConnectorNaming.sourceConnectorName(pipeline.getId());
        if (connectorRepository.findByCrName(connectorName).isPresent()) {
            log.warn("[smoke-seed] connector CR name conflict — connector seed skipped ({})", connectorName);
            return;
        }

        ConnectorEntity connector = new ConnectorEntity();
        connector.setId(CONNECTOR_ID);
        connector.setPipelineId(pipeline.getId());
        connector.setCrName(connectorName);
        connector.setKind(ConnectorKind.SOURCE);
        connector.setConnectorClass(CONNECTOR_CLASS);
        connector.setState("FAILED");
        connector.setTasksMax(1);
        connector.setLastError("Smoke seed placeholder connector");
        connector.setUpdatedAt(Instant.now());
        connectorRepository.saveAndFlush(connector);
        log.info("[smoke-seed] smoke connector created: {}", connectorName);
    }

    private boolean isSmokeConnector(ConnectorEntity connector, PipelineEntity pipeline) {
        return pipeline.getId().equals(connector.getPipelineId())
                && ConnectorNaming.sourceConnectorName(pipeline.getId()).equals(connector.getCrName())
                && ConnectorKind.SOURCE == connector.getKind()
                && CONNECTOR_CLASS.equals(connector.getConnectorClass())
                && connector.getTasksMax() == 1;
    }

    private boolean ensureIncident(PipelineEntity pipeline) {
        Optional<IncidentEntity> existing = incidentRepository.findById(INCIDENT_ID);
        if (existing.isPresent()) {
            if (!isSmokeIncident(existing.get(), pipeline)) {
                log.warn("[smoke-seed] fixed incident id exists but is not the smoke incident — event seed skipped ({})",
                        INCIDENT_ID);
                return false;
            }
            return true;
        }

        IncidentEntity incident = new IncidentEntity();
        incident.setId(INCIDENT_ID);
        incident.setTenantId(WORKSPACE_ID);
        incident.setGroupingKey(INCIDENT_GROUPING_KEY);
        incident.setSeverity(INCIDENT_SEVERITY);
        incident.setStatus("OPEN");
        incident.setTitle(INCIDENT_TITLE);
        incident.setRca("Seeded incident for project-scoped read-tool smoke validation.");
        incident.setSourceType("PIPELINE");
        incident.setSourceId(pipeline.getId());
        incident.setOpenedAt(Instant.now());
        incidentRepository.saveAndFlush(incident);
        log.info("[smoke-seed] smoke incident created: {}", INCIDENT_ID);
        return true;
    }

    private boolean isSmokeIncident(IncidentEntity incident, PipelineEntity pipeline) {
        return WORKSPACE_ID.equals(incident.getTenantId())
                && pipeline.getId().equals(incident.getSourceId())
                && "PIPELINE".equals(incident.getSourceType())
                && INCIDENT_GROUPING_KEY.equals(incident.getGroupingKey())
                && INCIDENT_SEVERITY.equals(incident.getSeverity())
                && INCIDENT_TITLE.equals(incident.getTitle());
    }

    private void ensureEvent(PipelineEntity pipeline) {
        Optional<EventEntity> existing = eventRepository.findById(EVENT_ID);
        if (existing.isPresent()) {
            if (!isSmokeEvent(existing.get(), pipeline)) {
                log.warn("[smoke-seed] fixed event id exists but is not the smoke event — skip ({})", EVENT_ID);
            }
            return;
        }

        EventEntity event = new EventEntity();
        event.setId(EVENT_ID);
        event.setTenantId(WORKSPACE_ID);
        event.setPipelineId(pipeline.getId());
        event.setIncidentId(INCIDENT_ID);
        event.setLevel(EventLevel.ERROR);
        event.setType(EVENT_TYPE);
        event.setMessage(EVENT_MESSAGE);
        event.setCategory(EVENT_CATEGORY);
        eventRepository.saveAndFlush(event);
        log.info("[smoke-seed] smoke event created: {}", EVENT_ID);
    }

    private boolean isSmokeEvent(EventEntity event, PipelineEntity pipeline) {
        return WORKSPACE_ID.equals(event.getTenantId())
                && pipeline.getId().equals(event.getPipelineId())
                && INCIDENT_ID.equals(event.getIncidentId())
                && EventLevel.ERROR == event.getLevel()
                && EVENT_TYPE.equals(event.getType())
                && EVENT_MESSAGE.equals(event.getMessage())
                && EVENT_CATEGORY.equals(event.getCategory());
    }
}
