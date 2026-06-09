package com.bifrost.ops.auth.security;

import com.bifrost.ops.auth.AuthPaths;

public final class SecurityPaths {

    public static final String ACTUATOR_HEALTH = "/actuator/health";
    public static final String ACTUATOR_INFO = "/actuator/info";
    public static final String V3_API_DOCS = "/v3/api-docs";
    public static final String V3_API_DOCS_GROUPED = "/v3/api-docs/**";
    public static final String SWAGGER_UI_ASSETS = "/swagger-ui/**";
    public static final String SWAGGER_UI_HTML = "/swagger-ui.html";
    public static final String SWAGGER_UI_INDEX_CSS = "/swagger-ui/index.css";
    public static final String INTERNAL_OPS = "/internal/ops/**";

    public static final String[] PUBLIC_AUTH_POST_PATHS = {
        AuthPaths.API_V1_REGISTER,
        AuthPaths.API_V1_LOGIN,
        AuthPaths.LEGACY_LOGIN
    };

    public static final String[] PROTECTED_AUTH_POST_PATHS = {
        AuthPaths.API_V1_REFRESH,
        AuthPaths.LEGACY_REFRESH
    };

    public static final String[] PROTECTED_AUTH_GET_PATHS = {
        AuthPaths.API_V1_ME,
        AuthPaths.LEGACY_ME
    };

    public static final String[] ACTUATOR_PATHS = {
        ACTUATOR_HEALTH,
        ACTUATOR_INFO
    };

    public static final String[] OPEN_API_PATHS = {
        V3_API_DOCS,
        V3_API_DOCS_GROUPED,
        SWAGGER_UI_ASSETS,
        SWAGGER_UI_HTML
    };

    public static final String[] INTERNAL_OPS_PATHS = {
        INTERNAL_OPS
    };

    private SecurityPaths() {
    }
}
