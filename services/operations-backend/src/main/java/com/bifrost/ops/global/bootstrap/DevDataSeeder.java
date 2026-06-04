package com.bifrost.ops.global.bootstrap;

import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 로컬(dev) 반복 테스트용 데모 계정 seed(#90).
 *
 * <p>seed된 계정이 없으면 매 테스트마다 {@code /api/v1/auth/register}를 호출해야 한다.
 * dev 프로파일에서만, 멱등하게(이미 있으면 skip) 데모 사용자와 home 워크스페이스를 만든다.
 * 자격증명은 환경변수로 덮어쓸 수 있다. KafkaUser/ACL 프로비저닝(K8s)은 호출하지 않는다
 * — 로컬에서 클러스터 없이 로그인 흐름을 바로 검증하기 위함.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;

    private final boolean enabled;
    private final String email;
    private final String password;
    private final String workspaceName;
    private final String namespace;

    public DevDataSeeder(UserRepository userRepository,
                         WorkspaceRepository workspaceRepository,
                         PasswordEncoder passwordEncoder,
                         @Value("${dev.seed.enabled:true}") boolean enabled,
                         @Value("${dev.seed.email:ta@bifrost.io}") String email,
                         @Value("${dev.seed.password:ta123456}") String password,
                         @Value("${dev.seed.workspace-name:Demo Team}") String workspaceName,
                         @Value("${dev.seed.namespace:demo-team}") String namespace) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.email = email;
        this.password = password;
        this.workspaceName = workspaceName;
        this.namespace = namespace;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (userRepository.existsByEmail(email)) {
            log.info("[dev-seed] 데모 계정 이미 존재 — skip ({})", email);
            return;
        }
        if (workspaceRepository.existsByName(workspaceName) || workspaceRepository.existsByNamespace(namespace)) {
            log.warn("[dev-seed] 워크스페이스 이름/namespace 충돌 — seed skip ({} / {})", workspaceName, namespace);
            return;
        }

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(workspaceName);
        workspace.setNamespace(namespace);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE); // 로컬 데모: 프로비저닝 생략, 바로 ACTIVE

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        workspace.setOwnerUserId(user.getId());

        workspace = workspaceRepository.saveAndFlush(workspace);
        user.setTenantId(workspace.getId());
        userRepository.saveAndFlush(user);

        log.info("[dev-seed] 데모 계정 생성: {} / (pw: {}자) → 워크스페이스 '{}' ({})",
                email, password.length(), workspaceName, namespace);
    }
}
