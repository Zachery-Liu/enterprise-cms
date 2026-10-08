package com.zachery.cms.modules.identity.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.modules.identity.persistence.mapper.RbacAssignmentMapper;
import com.zachery.cms.security.session.SessionKeys;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "spring.datasource.url=jdbc:h2:mem:b13_assignments;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(RbacAssignmentIntegrationTest.ProbeConfiguration.class)
class RbacAssignmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutationProbe probe;
    @Autowired RbacAssignmentMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    long admin, plain, target, disabledUser, deletedUser;
    long adminRole, editor, reviewer, disabledRole, deletedRole;
    long assign, manage, read, audit, disabledPermission, deletedPermission;

    record Client(MockHttpSession session, String csrf) {}

    @BeforeEach void seed() {
        probe.reset();
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        admin = user("admin", "ACTIVE", 0);
        plain = user("plain", "ACTIVE", 0);
        target = user("target", "ACTIVE", 0);
        disabledUser = user("disabled", "DISABLED", 0);
        deletedUser = user("deleted", "ACTIVE", 1);
        adminRole = role("ADMIN", "ENABLED", 0);
        editor = role("EDITOR", "ENABLED", 0);
        reviewer = role("REVIEWER", "ENABLED", 0);
        disabledRole = role("DISABLED", "DISABLED", 0);
        deletedRole = role("DELETED", "ENABLED", 1);
        assign = permission("cms:role:assign", "ENABLED", 0);
        manage = permission("cms:permission:manage", "ENABLED", 0);
        read = permission("cms:article:read", "ENABLED", 0);
        audit = permission("cms:article:audit", "ENABLED", 0);
        disabledPermission = permission("cms:test:disabled", "DISABLED", 0);
        deletedPermission = permission("cms:test:deleted", "ENABLED", 1);
        link(admin, adminRole); link(plain, editor); link(target, editor);
        grant(adminRole, assign); grant(adminRole, manage); grant(adminRole, read);
        grant(editor, read); grant(reviewer, audit);
    }

    @AfterEach void resetProbe() { probe.reset(); }

    @Test void replacingRolesDeduplicatesAndUsesTheAuthenticatedCreator() throws Exception {
        Client client = client(admin);
        Map<String, Object> body = Map.of("roleIds", List.of(reviewer, reviewer), "createdBy", plain, "userId", plain);
        for (int i = 0; i < 2; i++) write(client, userPath(target), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK")).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(roles(target)).containsExactly(reviewer);
        assertThat(roles(plain)).containsExactly(editor);
        assertThat(jdbc.queryForObject("SELECT created_by FROM sys_user_role WHERE user_id=?", Long.class, target)).isEqualTo(admin);
    }

    @Test void replacingPermissionsDeduplicatesAndClearsObsoleteGrants() throws Exception {
        Client client = client(admin);
        for (int i = 0; i < 2; i++) write(client, rolePath(editor), Map.of("permissionIds", List.of(audit, audit)))
                .andExpect(status().isOk());
        assertThat(permissions(editor)).containsExactly(audit);
        assertThat(jdbc.queryForObject("SELECT created_by FROM sys_role_permission WHERE role_id=?", Long.class, editor)).isEqualTo(admin);
    }

    @Test void emptySetsCanClearOrdinaryUsersAndNonAdministrativeRoles() throws Exception {
        Client client = client(admin);
        write(client, userPath(target), Map.of("roleIds", List.of())).andExpect(status().isOk());
        write(client, rolePath(editor), Map.of("permissionIds", List.of())).andExpect(status().isOk());
        assertThat(roles(target)).isEmpty();
        assertThat(permissions(editor)).isEmpty();
    }

    @Test void anonymousUnauthorizedAndMissingCsrfRequestsCannotWrite() throws Exception {
        for (String path : List.of(userPath(target), rolePath(editor))) {
            Map<String, Object> body = path.contains("/users/") ? Map.of("roleIds", List.of()) : Map.of("permissionIds", List.of());
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
            write(client(plain), path, body).andExpect(status().isForbidden());
            mvc.perform(put(path).session(client(admin).session()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        }
        assertThat(roles(target)).containsExactly(editor);
        assertThat(permissions(editor)).containsExactly(read);
    }

    @Test void eachManagementPermissionProtectsItsOwnWriteOperation() throws Exception {
        grant(editor, assign);
        Client limited = client(plain);
        write(limited, userPath(target), Map.of("roleIds", List.of(reviewer))).andExpect(status().isOk());
        write(limited, rolePath(reviewer), Map.of("permissionIds", List.of(read))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?", editor, assign);
        grant(editor, manage);
        write(limited, rolePath(reviewer), Map.of("permissionIds", List.of(read))).andExpect(status().isOk());
        write(limited, userPath(target), Map.of("roleIds", List.of(editor))).andExpect(status().isForbidden());
    }

    @Test void invalidCollectionsAreRejectedWithoutChangingRelationships() throws Exception {
        Client client = client(admin);
        List<Object> invalid = Arrays.asList(null, List.of(0L), List.of(-1L), Arrays.asList(editor, null), Collections.nCopies(501, editor));
        for (Object ids : invalid) {
            Map<String, Object> body = new HashMap<>(); body.put("roleIds", ids);
            write(client, userPath(target), body).andExpect(status().isBadRequest());
            body.clear(); body.put("permissionIds", ids);
            write(client, rolePath(editor), body).andExpect(status().isBadRequest());
        }
        write(client, userPath(target), Map.of()).andExpect(status().isBadRequest());
        write(client, rolePath(editor), Map.of()).andExpect(status().isBadRequest());
        assertThat(roles(target)).containsExactly(editor);
        assertThat(permissions(editor)).containsExactly(read);
    }

    @Test void unavailableRoleOrPermissionRejectsTheWholeRequest() throws Exception {
        Client client = client(admin);
        for (long id : List.of(disabledRole, deletedRole, Long.MAX_VALUE))
            write(client, userPath(target), Map.of("roleIds", List.of(reviewer, id))).andExpect(status().isBadRequest());
        for (long id : List.of(disabledPermission, deletedPermission, Long.MAX_VALUE))
            write(client, rolePath(editor), Map.of("permissionIds", List.of(audit, id))).andExpect(status().isBadRequest());
        assertThat(roles(target)).containsExactly(editor);
        assertThat(permissions(editor)).containsExactly(read);
    }

    @Test void unavailableTargetsReturnConsistentErrors() throws Exception {
        Client client = client(admin);
        for (String id : List.of("0", "-1", "bad", "9223372036854775808")) {
            write(client, "/api/users/" + id + "/roles", Map.of("roleIds", List.of())).andExpect(status().isBadRequest());
            write(client, "/api/roles/" + id + "/permissions", Map.of("permissionIds", List.of())).andExpect(status().isBadRequest());
        }
        write(client, userPath(deletedUser), Map.of("roleIds", List.of())).andExpect(status().isNotFound());
        write(client, userPath(Long.MAX_VALUE), Map.of("roleIds", List.of())).andExpect(status().isNotFound());
        write(client, rolePath(deletedRole), Map.of("permissionIds", List.of())).andExpect(status().isNotFound());
        write(client, rolePath(Long.MAX_VALUE), Map.of("permissionIds", List.of())).andExpect(status().isNotFound());
        write(client, userPath(disabledUser), Map.of("roleIds", List.of())).andExpect(status().isConflict());
        write(client, rolePath(disabledRole), Map.of("permissionIds", List.of())).andExpect(status().isConflict());
    }

    @Test void lastAdministratorCannotRemoveOwnRoleAndRollbackPreservesOriginalRows() throws Exception {
        var original = jdbc.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", admin);
        write(client(admin), userPath(admin), Map.of("roleIds", List.of(editor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON_CONFLICT"));
        assertThat(jdbc.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", admin)).isEqualTo(original);
        assertThat(mapper.hasRbacAdministrator()).isTrue();
    }

    @Test void lastAdministratorPermissionsCannotBeRemovedThroughRoleConfiguration() throws Exception {
        var original = jdbc.queryForList("SELECT * FROM sys_role_permission WHERE role_id=? ORDER BY id", adminRole);
        write(client(admin), rolePath(adminRole), Map.of("permissionIds", List.of(read))).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("SELECT * FROM sys_role_permission WHERE role_id=? ORDER BY id", adminRole)).isEqualTo(original);
    }

    @Test void managementCanBeTransferredToAnotherAccountWithoutDependingOnAdminRoleName() throws Exception {
        long successor = role("SUCCESSOR", "ENABLED", 0);
        grant(successor, assign); grant(successor, manage); link(plain, successor);
        write(client(admin), userPath(admin), Map.of("roleIds", List.of())).andExpect(status().isOk());
        assertThat(mapper.hasRbacAdministrator()).isTrue();
        write(client(plain), userPath(target), Map.of("roleIds", List.of(reviewer))).andExpect(status().isOk());
        write(client(admin), userPath(target), Map.of("roleIds", List.of(editor))).andExpect(status().isForbidden());
    }

    @Test void grantChangesAppearInTheExistingTargetSessionOnTheNextRequest() throws Exception {
        Client manager = client(admin);
        Client user = client(target);
        write(manager, userPath(target), Map.of("roleIds", List.of(reviewer))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(user.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCodes[0]").value("REVIEWER"))
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("cms:article:audit"));
        write(manager, rolePath(reviewer), Map.of("permissionIds", List.of())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(user.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissionCodes", hasSize(0)));
    }

    @Test void userRoleTransactionRollsBackEvenAfterReplacementInsertExecuted() throws Exception {
        var original = jdbc.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", target);
        probe.failAfter("insertUserRoles");
        write(client(admin), userPath(target), Map.of("roleIds", List.of(reviewer)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
        assertThat(probe.insertExecuted).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", target)).isEqualTo(original);
    }

    @Test void rolePermissionTransactionRollsBackEvenAfterReplacementInsertExecuted() throws Exception {
        var original = jdbc.queryForList("SELECT * FROM sys_role_permission WHERE role_id=?", editor);
        probe.failAfter("insertRolePermissions");
        write(client(admin), rolePath(editor), Map.of("permissionIds", List.of(audit))).andExpect(status().isInternalServerError());
        assertThat(probe.insertExecuted).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM sys_role_permission WHERE role_id=?", editor)).isEqualTo(original);
    }

    @Test void concurrentReplacementKeepsOneCompleteTargetSet() throws Exception {
        Client first = client(admin), second = client(admin);
        List<Integer> statuses = concurrently(
                () -> write(first, userPath(target), Map.of("roleIds", List.of(editor, reviewer))).andReturn().getResponse().getStatus(),
                () -> write(second, userPath(target), Map.of("roleIds", List.of(reviewer))).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactly(200, 200);
        assertThat(roles(target)).isIn(List.of(editor, reviewer), List.of(reviewer));
    }

    @Test void concurrentAdministratorRemovalsCannotRemoveBothManagers() throws Exception {
        long secondRole = role("SECOND_MANAGER", "ENABLED", 0);
        grant(secondRole, assign); grant(secondRole, manage); link(plain, secondRole);
        Client first = client(admin), second = client(plain);
        List<Integer> statuses = concurrently(
                () -> write(first, userPath(admin), Map.of("roleIds", List.of())).andReturn().getResponse().getStatus(),
                () -> write(second, rolePath(secondRole), Map.of("permissionIds", List.of())).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(mapper.hasRbacAdministrator()).isTrue();
    }

    @Test void authorityRevokedWhileWaitingForLockCannotUseOldRequestSnapshot() throws Exception {
        grant(editor, assign);
        Client limited = client(plain);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch arriving = new CountDownLatch(1);
            probe.beforeLock = arriving;
            Future<Integer> response = new TransactionTemplate(transactions).execute(transaction -> {
                jdbc.queryForObject("SELECT id FROM sys_permission WHERE id=? FOR UPDATE", Long.class, assign);
                Future<Integer> future = pool.submit(() -> write(limited, userPath(target), Map.of("roleIds", List.of(reviewer)))
                        .andReturn().getResponse().getStatus());
                try { assertThat(arriving.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?", editor, assign);
                return future;
            });
            assertThat(response.get(15, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(roles(target)).containsExactly(editor);
        } finally { pool.shutdownNow(); }
    }

    private Client client(long userId) throws Exception {
        MockHttpSession session = new MockHttpSession(null, "b13-session-" + UUID.randomUUID());
        session.setAttribute(SessionKeys.CURRENT_USER_ID, userId);
        var response = mvc.perform(get("/api/auth/csrf-token").session(session)).andExpect(status().isOk()).andReturn();
        return new Client(session, json.readTree(response.getResponse().getContentAsString()).at("/data/token").asText());
    }
    private ResultActions write(Client client, String path, Object body) throws Exception {
        return mvc.perform(put(path).session(client.session()).header("X-CSRF-Token", client.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private String userPath(long id) { return "/api/users/" + id + "/roles"; }
    private String rolePath(long id) { return "/api/roles/" + id + "/permissions"; }
    private List<Long> roles(long id) { return jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id", Long.class, id); }
    private List<Long> permissions(long id) { return jdbc.queryForList("SELECT permission_id FROM sys_role_permission WHERE role_id=? ORDER BY permission_id", Long.class, id); }
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
    private void link(long user, long role) { jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)", user, role, admin); }
    private void grant(long role, long permission) { jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)", role, permission, admin); }
    private List<Integer> concurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return first.call(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration { @Bean MutationProbe mutationProbe() { return new MutationProbe(); } }
    @Intercepts({
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class MutationProbe implements Interceptor {
        private String failMethod;
        volatile boolean insertExecuted;
        volatile CountDownLatch beforeLock;
        void failAfter(String method) { failMethod = method; }
        void reset() { failMethod = null; insertExecuted = false; beforeLock = null; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (id.endsWith("RbacAssignmentMapper.lockManagementPermission") && beforeLock != null) beforeLock.countDown();
            Object result = invocation.proceed();
            if (failMethod != null && id.endsWith("RbacAssignmentMapper." + failMethod)) {
                insertExecuted = true;
                throw new IllegalStateException("B13 synthetic failure after replacement SQL");
            }
            return result;
        }
    }
}
