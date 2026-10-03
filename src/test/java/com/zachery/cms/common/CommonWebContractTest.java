package com.zachery.cms.common;

import com.zachery.cms.common.api.*;
import com.zachery.cms.common.context.*;
import com.zachery.cms.common.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = CommonWebContractTest.TestConfig.class)
class CommonWebContractTest {
    @Configuration(proxyBeanMethods = false)
    @Import({ProbeController.class, GlobalExceptionHandler.class, RequestIdFilter.class})
    static class TestConfig {}

    @Autowired MockMvc mvc;

    @Test
    void successAndEmptyPageUseContractFieldsAndSameRequestId() throws Exception {
        mvc.perform(get("/probe/page").header("X-Request-Id", "A_article-123"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "A_article-123"))
                .andExpect(jsonPath("$.requestId").value("A_article-123"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void invalidOrMissingIdIsReplacedByUuid() throws Exception {
        for (String id : List.of("bad id", "x".repeat(65), "a/b")) {
            var response = mvc.perform(get("/probe/page").header("X-Request-Id", id))
                    .andExpect(status().isOk()).andReturn().getResponse();
            String generated = response.getHeader("X-Request-Id");
            assertThat(generated).matches("[0-9a-f-]{36}");
            assertThat(response.getContentAsString()).contains(generated);
        }
        mvc.perform(get("/probe/page")).andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void beanValidationReturnsFieldErrorsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"secret-value-too-long\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.errors[0].field").value("name"))
                .andExpect(jsonPath("$.data.errors[0].message").value("名称长度必须在 1 到 8 之间"))
                .andExpect(content().string(not(containsString("secret-value-too-long"))));
    }

    @Test
    void validBodiesPassAndManualValidationKeepsFieldDetails() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"valid\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("valid"));
        mvc.perform(get("/probe/manual-validation").header("X-Request-Id", "manual-error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.errors[0].field").value("page"))
                .andExpect(jsonPath("$.requestId").value("manual-error"));
    }

    @Test
    void malformedJsonAndMissingBodyDoNotLeakParserDetails() throws Exception {
        for (String body : List.of("{\"name\":", "")) {
            mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data").value(nullValue()))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
    }

    @Test
    void methodParameterConstraintsUseStructuredValidationData() throws Exception {
        mvc.perform(get("/probe/number").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.errors[0].field").value("size"));
        mvc.perform(get("/probe/number").param("size", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @CsvSource({"400,COMMON_VALIDATION_ERROR", "401,AUTH_UNAUTHENTICATED",
            "403,AUTH_FORBIDDEN", "404,COMMON_NOT_FOUND", "409,COMMON_CONFLICT", "500,COMMON_INTERNAL_ERROR"})
    void businessErrorsKeepHttpStatusAndRequestId(int status, String code) throws Exception {
        mvc.perform(get("/probe/error/" + status).header("X-Request-Id", "error-id"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").value("error-id"))
                .andExpect(header().string("X-Request-Id", "error-id"));
    }

    @Test
    void customDomainExceptionKeepsItsCode() throws Exception {
        mvc.perform(get("/probe/custom"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARTICLE_STATUS_CONFLICT"));
    }

    @Test
    void databaseAndUnexpectedErrorsDoNotExposeInternalDetails() throws Exception {
        for (String endpoint : List.of("database", "optimistic", "unexpected")) {
            mvc.perform(get("/probe/" + endpoint))
                    .andExpect(status().is(endpoint.equals("unexpected") ? 500 : 409))
                    .andExpect(jsonPath("$.code").value(endpoint.equals("unexpected")
                            ? "COMMON_INTERNAL_ERROR" : "COMMON_CONFLICT"))
                    .andExpect(content().string(not(containsString("SELECT"))))
                    .andExpect(content().string(not(containsString("private-path"))))
                    .andExpect(content().string(not(containsString("secret-password"))));
        }
    }

    @Test
    void routingAndProtocolErrorsAlsoUseUnifiedEnvelope() throws Exception {
        mvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mvc.perform(put("/probe/body")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("COMMON_HTTP_ERROR"));
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("COMMON_HTTP_ERROR"));
    }

    @Test
    void timesAreSerializedAsUtcIsoStrings() throws Exception {
        mvc.perform(get("/probe/time"))
                .andExpect(jsonPath("$.data").value("2026-10-04T00:00:00.123Z"));
    }

    @RestController
    static class ProbeController {
        record Input(@NotBlank @Size(min = 1, max = 8, message = "名称长度必须在 1 到 8 之间") String name) {}
        @GetMapping("/probe/page") ApiResponse<PageResponse<String>> page() { return ApiResponse.ok(PageResponse.empty(1, 20)); }
        @PostMapping("/probe/body") ApiResponse<String> body(@Valid @RequestBody Input input) { return ApiResponse.ok(input.name()); }
        @GetMapping("/probe/number") ApiResponse<Integer> number(@RequestParam @Min(1) @Max(100) int size) { return ApiResponse.ok(size); }
        @GetMapping("/probe/time") ApiResponse<Instant> time() { return ApiResponse.ok(Instant.parse("2026-10-04T00:00:00.123Z")); }
        @GetMapping("/probe/error/{status}") void error(@PathVariable int status) {
            for (ErrorCode code : ErrorCode.values()) if (code.getHttpStatus().value() == status) throw new BusinessException(code);
        }
        @GetMapping("/probe/custom") void custom() { throw new BusinessException(HttpStatus.CONFLICT, "ARTICLE_STATUS_CONFLICT", "文章状态冲突"); }
        @GetMapping("/probe/manual-validation") void manualValidation() { throw new RequestValidationException("page", "页码必须大于等于 1"); }
        @GetMapping("/probe/database") void database() { throw new DataIntegrityViolationException("SELECT secret-password FROM private-path"); }
        @GetMapping("/probe/optimistic") void optimistic() { throw new OptimisticLockingFailureException("private-path"); }
        @GetMapping("/probe/unexpected") void unexpected() { throw new IllegalStateException("private-path secret-password"); }
    }
}
