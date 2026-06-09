package com.bifrost.ops.internalops;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * X-Agent-* 요청 헤더에서 agent context를 추출한다.
 * request_id가 없으면 random UUID를 생성해 반환한다.
 */
public final class AgentHeaders {

    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final String SAFE_REQUEST_ID = "[A-Za-z0-9._:-]+";

    public static final String X_AGENT_REQUEST_ID = "X-Agent-Request-Id";
    public static final String X_REQUEST_ID       = "X-Request-Id";
    public static final String X_AGENT_ID         = "X-Agent-Id";
    public static final String X_AGENT_RUN_ID     = "X-Agent-Run-Id";

    private AgentHeaders() {}

    public static String requestId(HttpServletRequest request) {
        String value = request.getHeader(X_AGENT_REQUEST_ID);
        if (value == null || value.isBlank()) {
            value = request.getHeader(X_REQUEST_ID);
        }
        return isSafeRequestId(value) ? value : UUID.randomUUID().toString();
    }

    private static boolean isSafeRequestId(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_REQUEST_ID_LENGTH
                && value.matches(SAFE_REQUEST_ID);
    }
}
