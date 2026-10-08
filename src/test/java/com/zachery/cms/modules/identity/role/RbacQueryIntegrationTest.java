package com.zachery.cms.modules.identity.role;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.modules.identity.persistence.mapper.RbacQueryMapper;
import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.session.SessionKeys;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "spring.datasource.url=jdbc:h2:mem:b12_rbac;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(RbacQueryIntegrationTest.CountConfiguration.class)
class RbacQueryIntegrationTest {
    @Autowired RbacQueryService service;
    @Autowired RbacQueryMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SqlCounter counter;
    long admin, plain, emptyUser, disabledUser, deletedUser;
    long adminRole, editorRole, emptyRole, disabledRole, deletedRole;
    long assign, manage, read, disabledPermission, deletedPermission;

    @BeforeEach
    void seedIsolatedDatabase() {
        counter.clear();
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        admin = user("admin", "ACTIVE", 0);
        plain = user("plain", "ACTIVE", 0);
        emptyUser = user("empty", "ACTIVE", 0);
        disabledUser = user("disabled", "DISABLED", 0);
        deletedUser = user("deleted", "ACTIVE", 1);
        adminRole = role("ADMIN", "ENABLED", 0);
        editorRole = role("EDITOR", "ENABLED", 0);
        emptyRole = role("USER", "ENABLED", 0);
        disabledRole = role("DISABLED_ROLE", "DISABLED", 0);
        deletedRole = role("DELETED_ROLE", "ENABLED", 1);
        assign = permission("cms:role:assign", "ENABLED", 0);
        manage = permission("cms:permission:manage", "ENABLED", 0);
        read = permission("cms:article:read", "ENABLED", 0);
        disabledPermission = permission("cms:test:disabled", "DISABLED", 0);
        deletedPermission = permission("cms:test:deleted", "ENABLED", 1);
        linkUser(admin, adminRole);
        for (long id : List.of(editorRole, disabledRole, deletedRole)) linkUser(plain, id);
        linkUser(disabledUser, editorRole);
        linkUser(deletedUser, editorRole);
        for (long id : List.of(assign, manage, read)) grant(adminRole, id);
        for (long id : List.of(read, disabledPermission, deletedPermission)) grant(editorRole, id);
        grant(disabledRole, read);
        grant(deletedRole, read);
    }

    @AfterEach void clearCounter() { counter.clear(); }

    @Test
    void managementCatalogsExposeDisabledStateButNeverDeletedObjects() {
        assertThat(service.roles()).extracting(RoleSummary::code)
                .containsExactly("ADMIN", "DISABLED_ROLE", "EDITOR", "USER");
        assertThat(service.role(disabledRole).status()).isEqualTo("DISABLED");
        assertThat(service.permissions()).extracting(PermissionSummary::code)
                .containsExactly("cms:article:read", "cms:permission:manage", "cms:role:assign", "cms:test:disabled");
        assertThatThrownBy(() -> service.role(deletedRole)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.role(0)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void userRoleBatchDeduplicatesAndPreservesEmptyGroupsWithImmutableResults() {
        var result = service.rolesForUsers(List.of(plain, plain, emptyUser, disabledUser, deletedUser, Long.MAX_VALUE));
        assertThat(result).hasSize(5);
        assertThat(result.get(plain)).extracting(RoleSummary::code).containsExactly("EDITOR");
        for (long id : List.of(emptyUser, disabledUser, deletedUser, Long.MAX_VALUE)) assertThat(result.get(id)).isEmpty();
        assertThatThrownBy(() -> result.put(1L, List.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.get(plain).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rolePermissionBatchFiltersInactiveAndDeletedObjects() {
        var result = service.permissionsForRoles(List.of(editorRole, editorRole, emptyRole, disabledRole, deletedRole, Long.MAX_VALUE));
        assertThat(result).hasSize(5);
        assertThat(result.get(editorRole)).extracting(PermissionSummary::code).containsExactly("cms:article:read");
        for (long id : List.of(emptyRole, disabledRole, deletedRole, Long.MAX_VALUE)) assertThat(result.get(id)).isEmpty();
        assertThat(service.rolePermissions(disabledRole)).isEmpty();
    }

    @Test
    void sixtyUsersAndSeveralRolesUseOneQueryPerBatch() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long id = user("batch_" + i, "ACTIVE", 0);
            ids.add(id);
            linkUser(id, editorRole);
            if (i % 2 == 0) linkUser(id, emptyRole);
        }
        counter.begin();
        var result = service.rolesForUsers(ids);
        assertThat(counter.end()).singleElement().asString().endsWith("RbacQueryMapper.selectRolesForUsers");
        assertThat(result).hasSize(60);
        for (int i = 0; i < ids.size(); i++) assertThat(result.get(ids.get(i))).hasSize(i % 2 == 0 ? 2 : 1);
        counter.begin();
        var permissions = service.permissionsForRoles(List.of(adminRole, editorRole, emptyRole));
        assertThat(counter.end()).singleElement().asString().endsWith("RbacQueryMapper.selectPermissionsForRoles");
        assertThat(permissions.get(adminRole)).hasSize(3);
        assertThat(permissions.get(editorRole)).hasSize(1);
        assertThat(permissions.get(emptyRole)).isEmpty();
    }

    @Test
    void emptyBatchesSkipSqlAndMapperEmptyInputsCannotReadAllRows() {
        counter.begin();
        assertThat(service.rolesForUsers(List.of())).isEmpty();
        assertThat(service.permissionsForRoles(List.of())).isEmpty();
        assertThat(counter.end()).isEmpty();
        assertThat(mapper.selectRolesForUsers(List.of())).isEmpty();
        assertThat(mapper.selectRolesForUsers(null)).isEmpty();
        assertThat(mapper.selectPermissionsForRoles(List.of())).isEmpty();
        assertThat(mapper.selectPermissionsForRoles(null)).isEmpty();
    }

    @Test
    void invalidOrOversizedBatchesFailBeforeSqlIncludingRepeatedIds() {
        counter.begin();
        for (Collection<Long> ids : Arrays.<Collection<Long>>asList(null, List.of(0L), List.of(-1L),
                Arrays.asList(plain, null), Collections.nCopies(501, plain),
                java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList())) {
            assertThatThrownBy(() -> service.rolesForUsers(ids)).isInstanceOf(RequestValidationException.class);
            assertThatThrownBy(() -> service.permissionsForRoles(ids)).isInstanceOf(RequestValidationException.class);
        }
        assertThat(counter.end()).isEmpty();
    }

    @Test
    void effectivePermissionUnionIsUniqueAndReflectsRelationRemoval() {
        linkUser(plain, adminRole);
        assertThat(service.effectivePermissionCodes(plain))
                .containsExactly("cms:article:read", "cms:permission:manage", "cms:role:assign");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=? AND role_id=?", plain, adminRole);
        assertThat(service.effectivePermissionCodes(plain)).containsExactly("cms:article:read");
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?", editorRole, read);
        assertThat(service.effectivePermissionCodes(plain)).isEmpty();
        for (long id : List.of(disabledUser, deletedUser, Long.MAX_VALUE)) assertThat(service.effectivePermissionCodes(id)).isEmpty();
    }

    @Test
    void databaseRejectsDuplicateRelationships() {
        assertThatThrownBy(() -> linkUser(plain, editorRole)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> grant(editorRole, read)).isInstanceOf(DuplicateKeyException.class);
        assertThat(service.rolesForUsers(List.of(plain)).get(plain)).hasSize(1);
        assertThat(service.rolePermissions(editorRole)).hasSize(1);
    }

    @Test
    void allManagementEndpointsRequireLoginAndTheirSpecificPermission() throws Exception {
        for (String path : List.of("/api/roles", "/api/roles/" + editorRole,
                "/api/roles/" + editorRole + "/permissions", "/api/permissions")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            mvc.perform(get(path).session(session(plain))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }
    }

    @Test
    void authorizedEndpointsReturnSafeDtosRequestIdAndConsistentErrors() throws Exception {
        MockHttpSession session = session(admin);
        mvc.perform(get("/api/roles").session(session).header("X-Request-Id", "b12-query-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(header().string("X-Request-Id", "b12-query-test"))
                .andExpect(jsonPath("$.requestId").value("b12-query-test"));
        mvc.perform(get("/api/roles/" + editorRole).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.*", hasSize(5))).andExpect(jsonPath("$.data.code").value("EDITOR"))
                .andExpect(jsonPath("$.data.status").value("ENABLED")).andExpect(jsonPath("$.data.version").doesNotExist());
        mvc.perform(get("/api/permissions").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(4)));
        mvc.perform(get("/api/roles/" + emptyRole + "/permissions").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        for (String id : List.of("0", "-1", "abc", "9223372036854775808"))
            mvc.perform(get("/api/roles/" + id).session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/roles/" + deletedRole).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/roles/" + Long.MAX_VALUE + "/permissions").session(session)).andExpect(status().isNotFound());
    }

    @Test
    void nextRequestObservesRevocationAndAdminHasNoImplicitBypass() throws Exception {
        MockHttpSession session = session(admin);
        mvc.perform(get("/api/roles").session(session)).andExpect(status().isOk());
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?", adminRole, assign);
        mvc.perform(get("/api/roles").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/permissions").session(session)).andExpect(status().isOk());
        grant(editorRole, assign);
        mvc.perform(get("/api/roles").session(session(plain))).andExpect(status().isOk());
        mvc.perform(get("/api/permissions").session(session(plain))).andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE id=?", manage);
        mvc.perform(get("/api/permissions").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void disablingRoleRemovesAccessAndDisablingUserInvalidatesSession() throws Exception {
        MockHttpSession session = session(admin);
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE id=?", adminRole);
        mvc.perform(get("/api/roles").session(session)).andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", admin);
        mvc.perform(get("/api/roles").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    private long user(String name, String status, int deleted) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status,deleted) VALUES (?, 'test-fixture-not-login', ?, ?, ?)", name, name, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }
    private long role(String code, String status, int deleted) {
        jdbc.update("INSERT INTO sys_role(code,name,status,deleted) VALUES (?, ?, ?, ?)", code, code, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, code);
    }
    private long permission(String code, String status, int deleted) {
        jdbc.update("INSERT INTO sys_permission(code,name,status,deleted) VALUES (?, ?, ?, ?)", code, code, status, deleted);
        return jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?", Long.class, code);
    }
    private void linkUser(long userId, long roleId) {
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?, ?, ?)", userId, roleId, admin);
    }
    private void grant(long roleId, long permissionId) {
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?, ?, ?)", roleId, permissionId, admin);
    }
    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession(null, "b12-session-" + UUID.randomUUID());
        session.setAttribute(SessionKeys.CURRENT_USER_ID, userId);
        return session;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountConfiguration {
        @Bean SqlCounter sqlCounter() { return new SqlCounter(); }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class SqlCounter implements Interceptor {
        private final ThreadLocal<List<String>> statements = new ThreadLocal<>();
        void begin() { statements.set(new ArrayList<>()); }
        List<String> end() { List<String> result = List.copyOf(statements.get()); clear(); return result; }
        void clear() { statements.remove(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (statements.get() != null) statements.get().add(((MappedStatement) invocation.getArgs()[0]).getId());
            return invocation.proceed();
        }
    }
}
