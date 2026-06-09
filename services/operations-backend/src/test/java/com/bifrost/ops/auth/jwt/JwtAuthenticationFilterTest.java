package com.bifrost.ops.auth.jwt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private JwtAuthenticationFilter filter;
    private String token;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        JwtService jwtService = new JwtService(SECRET, "bifrost-test", 1);
        filter = new JwtAuthenticationFilter(jwtService);
        token = jwtService.issue(userId, tenantId, "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAgentRunEventsAccessTokenQuery() throws Exception {
        doFilterWithAccessToken("/api/v1/agent/runs/run_123/events");

        assertAuthenticatedUser();
    }

    @Test
    void authenticatesAgentRunEventsAccessTokenQueryWithContextPath() throws Exception {
        doFilterWithAccessToken("/ops", "/ops/api/v1/agent/runs/run_123/events");

        assertAuthenticatedUser();
    }

    @Test
    void preservesWorkspaceEventsStreamAccessTokenQuery() throws Exception {
        doFilterWithAccessToken("/api/v1/workspaces/ws-123/events/stream");

        assertAuthenticatedUser();
    }

    @Test
    void ignoresAccessTokenQueryOnAgentRunEventsHistory() throws Exception {
        doFilterWithAccessToken("/api/v1/agent/runs/run_123/events/history");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresAccessTokenQueryOnBlankAgentRunId() throws Exception {
        doFilterWithAccessToken("/api/v1/agent/runs//events");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresAccessTokenQueryOnNestedAgentRunEventsPath() throws Exception {
        doFilterWithAccessToken("/api/v1/agent/runs/run_123/steps/events");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void doFilterWithAccessToken(String path) throws Exception {
        doFilterWithAccessToken("", path);
    }

    private void doFilterWithAccessToken(String contextPath, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setContextPath(contextPath);
        request.setParameter("access_token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked).isTrue();
    }

    private void assertAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal())
            .isEqualTo(new AuthenticatedUser(userId, tenantId, "user@example.com"));
    }
}
