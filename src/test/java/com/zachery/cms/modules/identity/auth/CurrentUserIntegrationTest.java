package com.zachery.cms.modules.identity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.context.*;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.persistence.AuditActorProvider;
import com.zachery.cms.modules.identity.persistence.entity.RoleEntity;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import com.zachery.cms.modules.identity.user.service.*;
import com.zachery.cms.security.annotation.AnonymousAccess;
import com.zachery.cms.security.session.SessionKeys;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.TestPropertySource(properties = "cms.db.url=jdbc:h2:mem:currentuserintegrationtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@org.springframework.test.context.ContextHierarchy({
    @org.springframework.test.context.ContextConfiguration(name = "root", classes = {com.zachery.cms.config.RootConfiguration.class, com.zachery.cms.support.TestDatabaseConfiguration.class, CurrentUserIntegrationTest.ProbeConfiguration.class}),
    @org.springframework.test.context.ContextConfiguration(name = "web", classes = {com.zachery.cms.config.WebConfiguration.class, com.zachery.cms.support.MockMvcConfiguration.class, CurrentUserIntegrationTest.ProbeController.class})
})
class CurrentUserIntegrationTest extends com.zachery.cms.support.SpringWebIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CurrentUserContext context;
    @Autowired UserReader reader;
    @Autowired UserSummaryMapper summaries;
    @Autowired AuditActorProvider actors;
    @Autowired QueryProbe queries;
    long alice, bob, disabled, deleted;
    long editor, reviewer, inactiveRole, deletedRole;
    long read, audit, disabledPermission, deletedPermission;

    @BeforeEach
    void seed() {
        queries.clear();
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        alice = user("alice", "ACTIVE", 0);
        bob = user("bob", "ACTIVE", 0);
        disabled = user("disabled", "DISABLED", 0);
        deleted = user("deleted", "ACTIVE", 1);
        editor = role("EDITOR", "ENABLED", 0);
        reviewer = role("REVIEWER", "ENABLED", 0);
        inactiveRole = role("INACTIVE", "DISABLED", 0);
        deletedRole = role("DELETED", "ENABLED", 1);
        read = permission("cms:article:read", "ENABLED", 0);
        audit = permission("cms:article:audit", "ENABLED", 0);
        disabledPermission = permission("cms:test:disabled", "DISABLED", 0);
        deletedPermission = permission("cms:test:deleted", "ENABLED", 1);
        for (long id : List.of(editor, reviewer, inactiveRole, deletedRole))
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)", alice, id, alice);
        grant(editor, read);
        grant(reviewer, read);
        grant(reviewer, audit);
        grant(editor, disabledPermission);
        grant(editor, deletedPermission);
    }

    @AfterEach void clearProbe() { queries.clear(); }

    @Test
    void meUsesSessionIdentityAndReturnsOnlyContractFields() throws Exception {
        mvc.perform(get("/api/auth/me").session(session(alice)).param("userId", String.valueOf(bob))
                        .header("X-User-Id", bob).header("X-Request-Id", "b10-current"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.requestId").value("b10-current"))
                .andExpect(jsonPath("$.data.*", hasSize(6)))
                .andExpect(jsonPath("$.data.userId").value(alice)).andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("EDITOR"))
                .andExpect(jsonPath("$.data.roleCodes[1]").value("REVIEWER"))
                .andExpect(jsonPath("$.data.roleCodes", hasSize(2)))
                .andExpect(jsonPath("$.data.permissionCodes", hasSize(2)))
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("cms:article:audit"))
                .andExpect(jsonPath("$.data.permissionCodes[1]").value("cms:article:read"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void userWithoutRolesCanReadOwnIdentity() throws Exception {
        mvc.perform(get("/api/auth/me").session(session(bob))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(bob))
                .andExpect(jsonPath("$.data.roleCodes", hasSize(0)))
                .andExpect(jsonPath("$.data.permissionCodes", hasSize(0)));
    }

    @Test
    void anonymousForgedOrInactiveSessionsAreRejected() throws Exception {
        mvc.perform(get("/api/auth/me").param("userId", String.valueOf(alice))).andExpect(status().isUnauthorized());
        for (Object id : List.of("" + alice, -1L, Long.MAX_VALUE, disabled, deleted)) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(SessionKeys.CURRENT_USER_ID, id);
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Test
    void followingRequestReloadsProfileRolesAndPermissions() throws Exception {
        MockHttpSession session = session(alice);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE sys_user SET display_name='Alice changed' WHERE id=?", alice);
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE id=?", reviewer);
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?", editor, read);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Alice changed"))
                .andExpect(jsonPath("$.data.roleCodes", hasSize(1)))
                .andExpect(jsonPath("$.data.permissionCodes", hasSize(0)));
    }

    @Test
    void disablingOrDeletingAccountInvalidatesExistingSession() throws Exception {
        for (long id : List.of(alice, bob)) {
            MockHttpSession session = session(id);
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
            if (id == alice) jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", id);
            else jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", id);
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Test
    void repeatedContextReadsReuseOnlyTheCurrentRequestSnapshot() throws Exception {
        queries.begin();
        MvcResult result = mvc.perform(get("/api/b10-test/context").session(session(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sameSnapshot").value(true)).andReturn();
        var calls = queries.end();
        assertThat(calls.stream().filter(q -> q.id().endsWith("selectRolesForUsers"))).hasSize(1);
        assertThat(calls.stream().filter(q -> q.id().endsWith("selectEffectivePermissionCodes"))).hasSize(1);
        assertThat(result.getRequest().getAttribute(SessionKeys.REQUEST_USER)).isNull();
        assertThat(result.getRequest().getAttribute(SessionKeys.CURRENT_USER_SNAPSHOT)).isNull();
        assertThat(context.getCurrentUser()).isEmpty();
        assertThat(actors.currentUserId()).isEmpty();
    }

    @Test
    void exceptionAndAnonymousRequestCannotRetainPreviousPrincipal() throws Exception {
        MvcResult result = mvc.perform(get("/api/b10-test/failure").session(session(alice)))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getRequest().getAttribute(SessionKeys.CURRENT_USER_SNAPSHOT)).isNull();
        assertThat(context.getCurrentUser()).isEmpty();
        assertThatThrownBy(() -> context.getRequiredUser()).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("AUTH_UNAUTHENTICATED"));
        mvc.perform(get("/api/b10-test/anonymous").session(session(alice)))
                .andExpect(status().isOk()).andExpect(content().string("false"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRequestsNeverMixUsers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (long id : List.of(alice, bob)) tasks.add(() -> {
                MockHttpSession session = session(id);
                for (int i = 0; i < 6; i++) {
                    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.userId").value(id));
                    assertThat(context.getCurrentUser()).isEmpty();
                }
                return null;
            });
            for (Future<Void> result : pool.invokeAll(tasks)) result.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void loginMeLogoutFlowUsesActualAuthenticationAndCsrf() throws Exception {
        // Synthetic fixture password: FixturePass123!; same deterministic PBKDF2 fixture as B-09.
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?",
                "{pbkdf2-sha256}600000$AAECAwQFBgcICQoLDA0ODw==$wpcF9hlr08mvG4XuvsbKoBoWwAQhRgzErA9WLtQ1Tx8=", alice);
        MvcResult csrf = mvc.perform(get("/api/auth/csrf-token")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"alice\",\"password\":\"FixturePass123!\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(alice));
        csrf = mvc.perform(get("/api/auth/csrf-token").session(session)).andExpect(status().isOk()).andReturn();
        token = json.readTree(csrf.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", token)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void auditFillUsesAuthenticatedActorWithoutAdditionalIdentityQueries() throws Exception {
        MockHttpSession session = session(alice);
        var csrf = mvc.perform(get("/api/auth/csrf-token").session(session)).andReturn();
        String token = json.readTree(csrf.getResponse().getContentAsString()).at("/data/token").asText();
        queries.begin();
        mvc.perform(post("/api/b10-test/audit").session(session).header("X-CSRF-Token", token)
                        .param("createdBy", String.valueOf(bob))).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(alice)).andExpect(jsonPath("$.updatedBy").value(alice));
        assertThat(queries.end()).hasSize(1); // Account validation only; actor lookup performs no SQL.
        assertThat(actors.currentUserId()).isEmpty();
    }

    @Test
    void userReaderPreservesDisabledAuthorsAndOmitsMissingOrDeletedUsers() throws Exception {
        var result = reader.findByIds(List.of(disabled, alice, alice, deleted, Long.MAX_VALUE));
        assertThat(result.keySet()).containsExactly(disabled, alice);
        assertThat(result.get(disabled).status()).isEqualTo(UserStatus.DISABLED);
        assertThat(result.get(alice).username()).isEqualTo("alice");
        assertThat(json.valueToTree(result.get(alice)).size()).isEqualTo(4);
        assertThat(json.writeValueAsString(result)).doesNotContain("password", "createdBy", "roleCodes");
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void userReaderFetchesSixtyAuthorsInOneQueryWithoutPasswordColumn() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) ids.add(user("author_" + i, "ACTIVE", 0));
        queries.begin();
        assertThat(reader.findByIds(ids)).hasSize(60);
        var calls = queries.end();
        assertThat(calls).singleElement().satisfies(q -> {
            assertThat(q.id()).endsWith("UserSummaryMapper.selectSummaries");
            assertThat(q.sql().toLowerCase(Locale.ROOT)).doesNotContain("password", "select *");
        });
    }

    @Test
    void emptyAndInvalidReaderInputsExecuteNoSql() {
        queries.begin();
        assertThat(reader.findByIds(List.of())).isEmpty();
        for (Collection<Long> ids : Arrays.<Collection<Long>>asList(null, List.of(0L), List.of(-1L),
                Arrays.asList(alice, null), Collections.nCopies(501, alice)))
            assertThatThrownBy(() -> reader.findByIds(ids)).isInstanceOf(RequestValidationException.class);
        assertThat(queries.end()).isEmpty();
        assertThat(summaries.selectSummaries(List.of())).isEmpty();
        assertThat(summaries.selectSummaries(null)).isEmpty();
    }

    @Test
    void currentUserDefensivelyCopiesRoleAndPermissionSets() {
        Set<String> roles = new HashSet<>(Set.of("EDITOR"));
        Set<String> permissions = new HashSet<>(Set.of("cms:article:read"));
        CurrentUser user = new CurrentUser(alice, "alice", "Alice", UserStatus.ACTIVE, roles, permissions);
        roles.clear();
        permissions.clear();
        assertThat(user.roleCodes()).containsExactly("EDITOR");
        assertThat(user.permissionCodes()).containsExactly("cms:article:read");
        assertThatThrownBy(() -> user.roleCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.permissionCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private long user(String name, String status, int deleted) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status,deleted) VALUES (?,'test-only',?,?,?)", name, name, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }
    private long role(String code, String status, int deleted) {
        jdbc.update("INSERT INTO sys_role(code,name,status,deleted) VALUES (?,?,?,?)", code, code, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, code);
    }
    private long permission(String code, String status, int deleted) {
        jdbc.update("INSERT INTO sys_permission(code,name,status,deleted) VALUES (?,?,?,?)", code, code, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?", Long.class, code);
    }
    private void grant(long role, long permission) {
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)", role, permission, alice);
    }
    private MockHttpSession session(long id) {
        MockHttpSession session = new MockHttpSession(null, "b10-session-" + UUID.randomUUID());
        session.setAttribute(SessionKeys.CURRENT_USER_ID, id);
        return session;
    }

    @RestController
    static class ProbeController {
        private final CurrentUserContext users;
        private final RoleMapper roles;
        ProbeController(CurrentUserContext users, RoleMapper roles) { this.users = users; this.roles = roles; }
        @GetMapping("/api/b10-test/context")
        Map<String, Boolean> context() { return Map.of("sameSnapshot", users.getRequiredUser() == users.getRequiredUser()); }
        @GetMapping("/api/b10-test/failure")
        void failure() { users.getRequiredUser(); throw new IllegalStateException("b10 expected failure"); }
        @GetMapping("/api/b10-test/anonymous") @AnonymousAccess
        boolean anonymous() { return users.getCurrentUser().isPresent(); }
        @PostMapping("/api/b10-test/audit")
        RoleEntity audit() {
            RoleEntity role = new RoleEntity();
            role.setCode("AUDIT_PROBE"); role.setName("Audit probe"); role.setStatus("ENABLED");
            roles.insert(role);
            role.setName("Updated probe"); role.setUpdatedBy(null);
            roles.updateById(role);
            return role;
        }
    }

    record Query(String id, String sql) {}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration { @Bean QueryProbe queryProbe() { return new QueryProbe(); } }
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class QueryProbe implements Interceptor {
        private final ThreadLocal<List<Query>> calls = new ThreadLocal<>();
        void begin() { calls.set(new ArrayList<>()); }
        List<Query> end() { var result = List.copyOf(calls.get()); clear(); return result; }
        void clear() { calls.remove(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (calls.get() != null) {
                MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
                calls.get().add(new Query(statement.getId(), statement.getBoundSql(invocation.getArgs()[1]).getSql()));
            }
            return invocation.proceed();
        }
    }
}
