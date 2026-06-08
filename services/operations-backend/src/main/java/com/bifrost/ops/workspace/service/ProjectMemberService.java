package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.persistence.entity.UserEntity;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.dto.ProjectMemberAddRequest;
import com.bifrost.ops.workspace.dto.ProjectMemberResponse;
import com.bifrost.ops.workspace.dto.ProjectMemberUpdateRequest;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProjectMemberService {

    private final ProjectMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    public ProjectMemberService(ProjectMemberRepository memberRepository,
                                WorkspaceRepository workspaceRepository,
                                UserRepository userRepository) {
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> list(UUID wsId, AuthenticatedUser principal) {
        requireManager(wsId, principal);
        List<ProjectMemberEntity> members = memberRepository.findByIdWorkspaceIdOrderByJoinedAtAsc(wsId);
        Map<UUID, UserEntity> users = userRepository.findAllById(
                members.stream().map(ProjectMemberEntity::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        return members.stream()
                .map(member -> toResponse(member, users.get(member.getUserId())))
                .toList();
    }

    @Transactional
    public ProjectMemberResponse add(UUID wsId, AuthenticatedUser principal, ProjectMemberAddRequest req) {
        requireManager(wsId, principal);
        UserEntity user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND_BY_EMAIL,
                        "이메일에 해당하는 사용자를 찾을 수 없습니다"));
        if (memberRepository.existsByIdWorkspaceIdAndIdUserId(wsId, user.getId())) {
            throw new ApiException(ErrorCode.MEMBER_ALREADY_EXISTS, "이미 워크스페이스 멤버입니다");
        }
        Role role = req.role() == Role.OWNER ? Role.ADMIN : req.role();
        ProjectMemberEntity member = memberRepository.saveAndFlush(new ProjectMemberEntity(wsId, user.getId(), role));
        return toResponse(member, user);
    }

    @Transactional
    public ProjectMemberResponse update(UUID wsId, UUID userId, AuthenticatedUser principal, ProjectMemberUpdateRequest req) {
        requireManager(wsId, principal);
        ProjectMemberEntity member = memberRepository.findByIdWorkspaceIdAndIdUserId(wsId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND, "멤버를 찾을 수 없습니다"));
        if (member.getRole() == Role.OWNER && req.role() != Role.OWNER) {
            throw new ApiException(ErrorCode.OWNER_DEMOTION_FORBIDDEN, "OWNER 역할은 변경할 수 없습니다");
        }
        member.setRole(req.role());
        ProjectMemberEntity saved = memberRepository.saveAndFlush(member);
        UserEntity user = userRepository.findById(userId).orElse(null);
        return toResponse(saved, user);
    }

    @Transactional
    public void remove(UUID wsId, UUID userId, AuthenticatedUser principal) {
        requireManager(wsId, principal);
        ProjectMemberEntity member = memberRepository.findByIdWorkspaceIdAndIdUserId(wsId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND, "멤버를 찾을 수 없습니다"));
        if (member.getRole() == Role.OWNER) {
            throw new ApiException(ErrorCode.OWNER_DEMOTION_FORBIDDEN, "OWNER는 삭제할 수 없습니다");
        }
        memberRepository.deleteByIdWorkspaceIdAndIdUserId(wsId, userId);
    }

    private void requireManager(UUID wsId, AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "인증이 필요합니다");
        }
        boolean hasRole = memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
                wsId, principal.userId(), List.of(Role.OWNER, Role.ADMIN));
        if (hasRole) {
            return;
        }
        if (workspaceRepository.existsByIdAndOwnerUserId(wsId, principal.userId())) {
            return;
        }
        throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 관리 권한이 없습니다");
    }

    private ProjectMemberResponse toResponse(ProjectMemberEntity member, UserEntity user) {
        return new ProjectMemberResponse(
                member.getWorkspaceId(),
                member.getUserId(),
                user == null ? null : user.getEmail(),
                member.getRole(),
                member.getJoinedAt());
    }
}
