package com.zachery.cms.modules.identity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.modules.identity.auth.service.PasswordHasher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "spring.datasource.url=jdbc:h2:mem:b08_registration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "cms.registration.default-role-code=USER"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordHasher passwords;

    record Token(MockHttpSession session, String value) {}

    @BeforeEach
    void prepareIsolatedDatabase() {
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO sys_role(code,name,status,built_in) VALUES ('USER','普通用户','ENABLED',1)");
        jdbc.update("INSERT INTO sys_role(code,name,status,built_in) VALUES ('ADMIN','管理员','ENABLED',1)");
    }

    @Test
    void registersOnlyTheConfiguredOrdinaryRoleAndNeverEchoesSecrets() throws Exception {
        Token token = token();
        Map<String, Object> input = new LinkedHashMap<>(Map.of("username", "  Alice.User  ", "password", "RegisterPass123!",
                "displayName", "  Alice  ", "roles", List.of("ADMIN"), "permissions", List.of("cms:user:manage"),
                "status", "DISABLED", "deleted", 1, "createdBy", 999));
        MvcResult result = register(token, input)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("alice.user"))
                .andExpect(jsonPath("$.data.displayName").value("Alice"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt", endsWith("Z")))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.requestId").value("registration-test"))
                .andExpect(header().string("X-Request-Id", "registration-test")).andReturn();
        long userId = json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, userId);
        assertThat(hash).startsWith("{pbkdf2-sha256}600000$").isNotEqualTo("RegisterPass123!");
        assertThat(passwords.matches("RegisterPass123!", hash)).isTrue();
        assertThat(jdbc.queryForList("SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=?",
                String.class, userId)).containsExactly("USER");
        assertThat(jdbc.queryForObject("SELECT created_by FROM sys_user_role WHERE user_id=?", Long.class, userId)).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT created_by FROM sys_user WHERE id=?", Long.class, userId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission", Integer.class)).isZero();
        assertThat(token.session().getAttribute("CMS_CURRENT_USER_ID")).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("RegisterPass123!", hash, token.value(), token.session().getId());
    }

    @ParameterizedTest
    @CsvSource({"ab,ValidPass123!,Alice,username", "bad name,ValidPass123!,Alice,username",
            "valid,short,Alice,password", "valid,ValidPass123!,' ',displayName"})
    void invalidInputsReturnFieldErrorsAndCreateNothing(String username, String password, String displayName, String field) throws Exception {
        register(token(), Map.of("username", username, "password", password, "displayName", displayName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem(field)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void passwordLengthLimitsAreEnforcedBeforeHashing() throws Exception {
        for (String password : List.of(" ", "x".repeat(129))) {
            register(token(), Map.of("username", "valid", "password", password))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.errors[*].field", hasItem("password")));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void duplicateAndLogicallyDeletedNamesCannotBeReused() throws Exception {
        Token token = token();
        register(token, Map.of("username", "Mixed.Case", "password", "RegisterPass123!")).andExpect(status().isCreated());
        register(token, Map.of("username", " mixed.case ", "password", "RegisterPass123!"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON_CONFLICT"));
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE username='mixed.case'");
        register(token, Map.of("username", "mixed.case", "password", "RegisterPass123!")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingDisabledAndDeletedDefaultRoleFailWithoutPartialUsers() throws Exception {
        Token token = token();
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE code='USER'");
        register(token, Map.of("username", "disabled-role", "password", "RegisterPass123!")).andExpect(status().isConflict());
        jdbc.update("UPDATE sys_role SET status='ENABLED',deleted=1 WHERE code='USER'");
        register(token, Map.of("username", "deleted-role", "password", "RegisterPass123!")).andExpect(status().isConflict());
        jdbc.update("DELETE FROM sys_role WHERE code='USER'");
        register(token, Map.of("username", "missing-role", "password", "RegisterPass123!")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void roleAssignmentFailureRollsBackTheInsertedUser() throws Exception {
        jdbc.execute("ALTER TABLE sys_user_role ADD CONSTRAINT b08_force_assignment_failure CHECK(user_id < 0)");
        try {
            register(token(), Map.of("username", "rollback-user", "password", "RegisterPass123!")).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE sys_user_role DROP CONSTRAINT b08_force_assignment_failure");
        }
    }

    @Test
    void concurrentDuplicateRequestsCreateExactlyOneAccountAndRole() throws Exception {
        Token first = token(), second = token();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Callable<Integer>> requests = List.of(
                    () -> concurrentRegister(first, barrier),
                    () -> concurrentRegister(second, barrier));
            var outcomes = new ArrayList<Integer>();
            for (Future<Integer> result : pool.invokeAll(requests)) outcomes.add(result.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class)).isEqualTo(1);
    }

    @Test
    void csrfTokensAreSessionBoundAndInvalidRequestsNeverCreateUsers() throws Exception {
        MvcResult anonymous = mvc.perform(get("/api/auth/csrf-token")).andExpect(status().isOk()).andReturn();
        assertThat(anonymous.getRequest().getSession(false)).isNotNull();
        Token first = token(), second = token();
        assertThat(first.value()).isNotEqualTo(second.value()).matches("[A-Za-z0-9_-]{43}");
        mvc.perform(get("/api/auth/csrf-token").session(first.session()))
                .andExpect(jsonPath("$.data.token").value(first.value()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        String body = json.writeValueAsString(Map.of("username", "csrf-user", "password", "RegisterPass123!"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mvc.perform(post("/api/auth/register").session(first.session()).header("X-CSRF-Token", second.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        MockHttpSession expired = first.session();
        expired.invalidate();
        mvc.perform(post("/api/auth/register").header("X-CSRF-Token", first.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void defaultRoleMigrationIsRepeatableAndDoesNotRestoreDisabledRoles() throws Exception {
        jdbc.update("DELETE FROM sys_role WHERE code='USER'");
        String script = Files.readString(Path.of("sql/004_registration_default_role.sql"));
        // H2 validates the DML; MySQL-specific SET directives are not run on H2.
        String dml = script.substring(script.indexOf("INSERT INTO"));
        jdbc.execute(dml);
        jdbc.execute(dml);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE code='USER'", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE code='USER'");
        jdbc.execute(dml);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_role WHERE code='USER'", String.class)).isEqualTo("DISABLED");
    }

    private int concurrentRegister(Token token, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return register(token, Map.of("username", "race-user", "password", "RegisterPass123!"))
                .andReturn().getResponse().getStatus();
    }

    private Token token() throws Exception {
        // Long identifiers avoid accidental matches with timestamps or numeric user IDs in leak assertions.
        MockHttpSession session = new MockHttpSession(null, "b08-session-" + UUID.randomUUID());
        MvcResult response = mvc.perform(get("/api/auth/csrf-token").session(session))
                .andExpect(status().isOk()).andReturn();
        return new Token((MockHttpSession) response.getRequest().getSession(false),
                json.readTree(response.getResponse().getContentAsString()).at("/data/token").asText());
    }

    private ResultActions register(Token token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/auth/register").session(token.session())
                .header("X-CSRF-Token", token.value()).header("X-Request-Id", "registration-test")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
