package com.bifrost.ops.global.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.EnvelopeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    GlobalExceptionHandlerTest.TestResourceConfig.class
})
@TestPropertySource(properties = {
    "spring.mvc.throw-exception-if-no-handler-found=true",
    "spring.web.resources.add-mappings=false"
})
class GlobalExceptionHandlerTest {

    private static final String JSON_CODE = "$.code";
    private static final String JSON_MESSAGE = "$.message";
    private static final String METHOD_ONLY_PATH = "/test/envelope/method-only";
    private static final String MEDIA_TYPE_PATH = "/test/envelope/media-type";
    private static final String NO_HANDLER_PATH = "/test/envelope/missing";
    private static final String STATIC_RESOURCE_PATTERN = "/test-static/**";
    private static final String STATIC_RESOURCE_MISS = "/test-static/missing.css";
    private static final String STATIC_RESOURCE_LOCATION = "classpath:/test-static/";
    private static final String UNSUPPORTED_XML_BODY = "<request/>";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noHandlerFoundUses404Envelope() throws Exception {
        mockMvc.perform(get(NO_HANDLER_PATH))
            .andExpect(status().is(ErrorCode.RESOURCE_NOT_FOUND.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.RESOURCE_NOT_FOUND)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.RESOURCE_NOT_FOUND))
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(NoHandlerFoundException.class));
    }

    @Test
    void noResourceFoundUses404Envelope() throws Exception {
        mockMvc.perform(get(STATIC_RESOURCE_MISS))
            .andExpect(status().is(ErrorCode.RESOURCE_NOT_FOUND.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.RESOURCE_NOT_FOUND)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.RESOURCE_NOT_FOUND))
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(NoResourceFoundException.class));
    }

    @Test
    void methodNotSupportedUses405Envelope() throws Exception {
        exceptionMockMvc().perform(post(METHOD_ONLY_PATH))
            .andExpect(status().is(ErrorCode.METHOD_NOT_ALLOWED.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.METHOD_NOT_ALLOWED)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.METHOD_NOT_ALLOWED))
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(HttpRequestMethodNotSupportedException.class));
    }

    @Test
    void mediaTypeNotSupportedUses415Envelope() throws Exception {
        exceptionMockMvc().perform(post(MEDIA_TYPE_PATH)
                .contentType(MediaType.APPLICATION_XML)
                .content(UNSUPPORTED_XML_BODY))
            .andExpect(status().is(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status().value()))
            .andExpect(jsonPath(JSON_CODE).value(code(ErrorCode.UNSUPPORTED_MEDIA_TYPE)))
            .andExpect(jsonPath(JSON_MESSAGE).value(ErrorMessages.UNSUPPORTED_MEDIA_TYPE))
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(HttpMediaTypeNotSupportedException.class));
    }

    private static String code(ErrorCode code) {
        return String.valueOf(code.code());
    }

    private static MockMvc exceptionMockMvc() {
        return MockMvcBuilders.standaloneSetup(new EnvelopeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @RestController
    public static class EnvelopeController {

        @GetMapping(METHOD_ONLY_PATH)
        String methodOnly() {
            return ErrorMessages.VALIDATION_FAILED;
        }

        @PostMapping(path = MEDIA_TYPE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> mediaType(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    @Configuration
    public static class TestResourceConfig implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler(STATIC_RESOURCE_PATTERN)
                .addResourceLocations(STATIC_RESOURCE_LOCATION);
        }
    }
}
