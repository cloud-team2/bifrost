package com.bifrost.ops.auth;

public final class AuthPaths {

    public static final String API_V1_AUTH_BASE = "/api/v1/auth";
    public static final String LEGACY_AUTH_BASE = "/api/auth";

    public static final String REGISTER = "/register";
    public static final String LOGIN = "/login";
    public static final String REFRESH = "/refresh";
    public static final String ME = "/me";

    public static final String API_V1_REGISTER = API_V1_AUTH_BASE + REGISTER;
    public static final String API_V1_LOGIN = API_V1_AUTH_BASE + LOGIN;
    public static final String API_V1_REFRESH = API_V1_AUTH_BASE + REFRESH;
    public static final String API_V1_ME = API_V1_AUTH_BASE + ME;

    public static final String LEGACY_LOGIN = LEGACY_AUTH_BASE + LOGIN;
    public static final String LEGACY_REFRESH = LEGACY_AUTH_BASE + REFRESH;
    public static final String LEGACY_ME = LEGACY_AUTH_BASE + ME;

    private AuthPaths() {
    }
}
