package com.zachery.cms.modules.identity.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.modules.identity.auth.controller.SessionController;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.security.annotation.AnonymousAccess;
import com.zachery.cms.security.session.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.TestPropertySource(properties = "cms.db.url=jdbc:h2:mem:sessionintegrationtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ContextHierarchy({
    @org.springframework.test.context.ContextConfiguration(name = "root", classes = {com.zachery.cms.config.RootConfiguration.class, com.zachery.cms.support.TestDatabaseConfiguration.class}),
    @org.springframework.test.context.ContextConfiguration(name = "web", classes = {com.zachery.cms.config.WebConfiguration.class, com.zachery.cms.support.MockMvcConfiguration.class, SessionIntegrationTest.Probes.class})
})
class SessionIntegrationTest extends com.zachery.cms.support.SpringWebIntegrationTest {
    private static final String PASSWORD = "FixturePass123!";
    private static final String HASH = "{pbkdf2-sha256}600000$AAECAwQFBgcICQoLDA0ODw==$wpcF9hlr08mvG4XuvsbKoBoWwAQhRgzErA9WLtQ1Tx8=";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    int port;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    private Long userId;

    record Token(MockHttpSession session, String value) {}

    @BeforeEach
    void seedIsolatedAccounts() {
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO sys_role(code,name,status,built_in) VALUES ('USER','普通用户','ENABLED',1)");
        for (String name : List.of("active-user", "disabled-user", "deleted-user"))
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status,deleted) VALUES (?,?,?,?,?)",
                    name, HASH, "Session test user", name.equals("disabled-user") ? "DISABLED" : "ACTIVE",
                    name.equals("deleted-user") ? 1 : 0);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='active-user'", Long.class);
    }

    @Test
    void loginRotatesSessionAndCsrfAndReturnsOnlySafeUserFields() throws Exception {
        Token before = token(null);
        String oldId = before.session().getId();
        MvcResult response = login(before, Map.of("username", " Active-User ", "password", PASSWORD,
                        "userId", 999999, "roles", List.of("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.username").value("active-user"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn();
        assertThat(before.session().getId()).isNotEqualTo(oldId);
        assertThat(before.session().getAttribute(SessionKeys.CURRENT_USER_ID)).isEqualTo(userId);
        Token after = token(before.session());
        assertThat(after.value()).isNotEqualTo(before.value());
        assertThat(response.getResponse().getContentAsString()).doesNotContain(PASSWORD, HASH, oldId, before.value(), after.value());
        mvc.perform(post("/api/test/session").session(after.session()).header("X-CSRF-Token", before.value()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/test/session").session(after.session()).header("X-CSRF-Token", after.value()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"active-user,wrong-password", "unknown-user,FixturePass123!",
            "disabled-user,FixturePass123!", "deleted-user,FixturePass123!"})
    void rejectedCredentialsHaveSameStatusAndMessageAndCreateNoLogin(String username, String password) throws Exception {
        Token token = token(null);
        String originalId = token.session().getId();
        login(token, Map.of("username", username, "password", password))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("账号或密码不正确，或账号不可用"));
        assertThat(token.session().getAttribute(SessionKeys.CURRENT_USER_ID)).isNull();
        assertThat(token.session().getId()).isEqualTo(originalId);
    }

    @Test
    void csrfIsRequiredForBothLoginAndLogout() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "active-user", "password", PASSWORD))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        Token first = token(null), other = token(null);
        login(new Token(first.session(), other.value()), Map.of("username", "active-user", "password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(first.session().getAttribute(SessionKeys.CURRENT_USER_ID)).isNull();
    }

    @Test
    void logoutInvalidatesSessionAndRepeatWithFreshCsrfIsSafe() throws Exception {
        Token loggedIn = loggedIn();
        mvc.perform(post("/api/auth/logout").session(loggedIn.session()).header("X-CSRF-Token", loggedIn.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        assertThat(loggedIn.session().isInvalid()).isTrue();
        mvc.perform(get("/api/test/session")).andExpect(status().isUnauthorized());
        Token fresh = token(null);
        mvc.perform(post("/api/auth/logout").session(fresh.session()).header("X-CSRF-Token", fresh.value()))
                .andExpect(status().isOk());
        assertThat(fresh.session().isInvalid()).isTrue();
    }

    @Test
    void protectedEndpointsRejectMissingSessionAndForgedIdentity() throws Exception {
        mvc.perform(get("/api/test/session").param("userId", userId.toString()).header("X-User-Id", userId))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/test/session")).andExpect(status().isUnauthorized());
        Token token = token(null);
        token.session().setAttribute(SessionKeys.CURRENT_USER_ID, userId.toString());
        mvc.perform(get("/api/test/session").session(token.session())).andExpect(status().isUnauthorized());
        assertThat(token.session().isInvalid()).isTrue();
        mvc.perform(get("/api/test/public")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"status,'DISABLED'", "deleted,1"})
    void disablingOrDeletingAccountInvalidatesExistingSession(String column, String value) throws Exception {
        Token loggedIn = loggedIn();
        // Inputs here are fixed test cases, never request data.
        jdbc.update("UPDATE sys_user SET " + column + "=? WHERE id=?",
                column.equals("deleted") ? Integer.parseInt(value) : value, userId);
        mvc.perform(get("/api/test/session").session(loggedIn.session()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        assertThat(loggedIn.session().isInvalid()).isTrue();
    }

    @Test
    void eachProtectedRequestReadsFreshUserStateWithoutTrustingClientInput() throws Exception {
        Token loggedIn = loggedIn();
        jdbc.update("UPDATE sys_user SET display_name='Updated from database' WHERE id=?", userId);
        mvc.perform(get("/api/test/session").session(loggedIn.session()).param("userId", "9999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.displayName").value("Updated from database"));
    }

    @Test
    void loginDtoAndAuthenticationLogsExcludeCredentialsAndSessionIds() throws Exception {
        LoginRequest dto = new LoginRequest("active-user", PASSWORD);
        assertThat(dto.toString()).doesNotContain(PASSWORD);
        assertThat(json.writeValueAsString(dto)).doesNotContain("password", PASSWORD);
        Logger logger = (Logger) LoggerFactory.getLogger(SessionController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Token token = token(null);
            String oldId = token.session().getId();
            login(token, Map.of("username", "active-user", "password", PASSWORD)).andExpect(status().isOk());
            Token failure = token(null);
            login(failure, Map.of("username", "unknown-user", "password", PASSWORD)).andExpect(status().isUnauthorized());
            String logs = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(logs).contains("result=SUCCESS", "result=FAILURE", "requestId=login-test")
                    .doesNotContain(PASSWORD, HASH, oldId, token.value(), failure.value(), "unknown-user");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void invalidBodyUsesFieldErrorsWithoutEchoingPassword() throws Exception {
        Token token = token(null);
        login(token, Map.of("username", "active-user", "password", "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("password")))
                .andExpect(content().string(not(containsString("x".repeat(129)))));
        assertThat(token.session().getAttribute(SessionKeys.CURRENT_USER_ID)).isNull();
    }

    @Test
    void newlyRegisteredAccountCanLogInWithoutAnAutomaticRegistrationSession() throws Exception {
        Token first = token(null);
        mvc.perform(post("/api/auth/register").session(first.session()).header("X-CSRF-Token", first.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "new-account", "password", PASSWORD))))
                .andExpect(status().isCreated());
        assertThat(first.session().getAttribute(SessionKeys.CURRENT_USER_ID)).isNull();
        login(first, Map.of("username", "new-account", "password", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void realHttpCookiesRotateAndOldSessionCannotBeReusedAfterLogout() throws Exception {
        try (var server = new com.zachery.cms.support.TestHttpServer(web.getParent(), Probes.class)) {
        port = server.port();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        HttpClient staleClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> tokenResponse = client.send(http("/api/auth/csrf-token").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode()).isEqualTo(200);
        String oldCookie = tokenResponse.headers().firstValue("set-cookie").orElseThrow();
        assertThat(oldCookie.toLowerCase(Locale.ROOT)).contains("httponly", "samesite=lax", "path=/");
        String token = json.readTree(tokenResponse.body()).at("/data/token").asText();
        HttpResponse<String> loggedIn = client.send(http("/api/auth/login").header("X-CSRF-Token", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", "active-user", "password", PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        String newCookie = loggedIn.headers().firstValue("set-cookie").orElseThrow();
        assertThat(newCookie.split(";", 2)[0]).isNotEqualTo(oldCookie.split(";", 2)[0]);
        assertThat(client.send(http("/api/test/session").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(staleClient.send(http("/api/test/session").header("Cookie", oldCookie.split(";", 2)[0]).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        HttpResponse<String> timeout = client.send(http("/api/test/session-timeout").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(timeout.body()).at("/data").asInt()).isEqualTo(1800);
        String rotatedToken = json.readTree(client.send(http("/api/auth/csrf-token").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).at("/data/token").asText();
        assertThat(rotatedToken).isNotEqualTo(token);
        assertThat(client.send(http("/api/auth/logout").header("X-CSRF-Token", rotatedToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(staleClient.send(http("/api/test/session").header("Cookie", newCookie.split(";", 2)[0]).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    }

    private HttpRequest.Builder http(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private Token loggedIn() throws Exception {
        Token token = token(null);
        login(token, Map.of("username", "active-user", "password", PASSWORD)).andExpect(status().isOk());
        return token(token.session());
    }

    private Token token(MockHttpSession session) throws Exception {
        if (session == null) session = new MockHttpSession(null, "b09-session-" + UUID.randomUUID());
        MvcResult result = mvc.perform(get("/api/auth/csrf-token").session(session)).andExpect(status().isOk()).andReturn();
        return new Token(session, json.readTree(result.getResponse().getContentAsString()).at("/data/token").asText());
    }

    private ResultActions login(Token token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/auth/login").session(token.session())
                .header("X-CSRF-Token", token.value()).header("X-Request-Id", "login-test")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Configuration(proxyBeanMethods = false)
    static class Probes {
        @Bean SessionProbeController sessionProbeController() { return new SessionProbeController(); }
    }

    @RestController
    static class SessionProbeController {
        @GetMapping("/api/test/session")
        ApiResponse<LoginUser> read(HttpServletRequest request) {
            return ApiResponse.ok((LoginUser) request.getAttribute(SessionKeys.REQUEST_USER));
        }
        @PostMapping("/api/test/session") ApiResponse<Void> write() { return ApiResponse.ok(); }
        @GetMapping("/api/test/session-timeout") ApiResponse<Integer> timeout(HttpServletRequest request) {
            return ApiResponse.ok(request.getSession(false).getMaxInactiveInterval());
        }
        @GetMapping("/api/test/public") @AnonymousAccess ApiResponse<Void> anonymous() { return ApiResponse.ok(); }
    }
}
