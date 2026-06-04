package com.bifrost.ops.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 워크스페이스 생성 요청(#72). namespace/projectKey는 name에서 슬러그로 자동 생성한다.
 */
public record WorkspaceCreateRequest(
    @NotBlank @Size(min = 2, max = 100) String name
) {}
