package com.bifrost.ops.internalops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** FastAPI SpringOpsResponse-compatible error body. */
public record OpsError(
        String code,
        String message,
        boolean retryable,
        @JsonProperty("required_action") String requiredAction
) {}
