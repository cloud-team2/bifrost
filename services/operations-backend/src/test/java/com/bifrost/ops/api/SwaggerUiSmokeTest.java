package com.bifrost.ops.api;

import com.bifrost.ops.auth.jwt.JwtAuthenticationFilter;
import com.bifrost.ops.auth.jwt.JwtService;
import com.bifrost.ops.auth.security.JwtAuthenticationEntryPoint;
import com.bifrost.ops.auth.security.SecurityConfig;
import com.bifrost.ops.auth.security.SecurityPaths;
import com.bifrost.ops.global.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocUIConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SwaggerUiSmokeTest.SmokeController.class)
@Import({
    SecurityConfig.class,
    JwtAuthenticationEntryPoint.class,
    JwtAuthenticationFilter.class,
    GlobalExceptionHandler.class
})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocUIConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    SwaggerConfig.class,
    SwaggerUiConfigProperties.class,
    SwaggerUiConfigParameters.class,
    SwaggerUiOAuthProperties.class
})
@TestPropertySource(properties = {
    "spring.mvc.throw-exception-if-no-handler-found=true",
    "spring.web.resources.add-mappings=false",
    SwaggerUiSmokeTest.SWAGGER_UI_PATH_PROPERTY
})
class SwaggerUiSmokeTest {

    public static final String SWAGGER_UI_PATH_PROPERTY =
        "springdoc.swagger-ui.path=" + SecurityPaths.SWAGGER_UI_HTML;
    private static final String SWAGGER_UI_INDEX_HTML = "/swagger-ui/index.html";
    private static final String OPENAPI_JSON_FIELD = "$.openapi";
    private static final String SMOKE_PATH = "/test/swagger-smoke";
    private static final String SMOKE_STATUS_KEY = "status";
    private static final String SMOKE_STATUS_VALUE = "ok";
    private static final String CSS_MEDIA_TYPE = "text/css";
    private static final String LOCATION = "Location";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @Test
    void swaggerUiHtmlRedirectIsPublic() throws Exception {
        mockMvc.perform(get(SecurityPaths.SWAGGER_UI_HTML))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(LOCATION, SWAGGER_UI_INDEX_HTML));
    }

    @Test
    void swaggerUiCssIsServedWithResourceMappingsDisabled() throws Exception {
        mockMvc.perform(get(SecurityPaths.SWAGGER_UI_INDEX_CSS))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf(CSS_MEDIA_TYPE)));
    }

    @Test
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get(SecurityPaths.V3_API_DOCS))
            .andExpect(status().isOk())
            .andExpect(jsonPath(OPENAPI_JSON_FIELD).exists());
    }

    @RestController
    static class SmokeController {

        @GetMapping(SMOKE_PATH)
        Map<String, String> smoke() {
            return Map.of(SMOKE_STATUS_KEY, SMOKE_STATUS_VALUE);
        }
    }
}
