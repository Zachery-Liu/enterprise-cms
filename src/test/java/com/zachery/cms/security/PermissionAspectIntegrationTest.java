package com.zachery.cms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.*;
import com.zachery.cms.security.annotation.*;
import com.zachery.cms.security.aspect.PermissionAspect;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "cms.db.url=jdbc:h2:mem:b14_permissions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ContextHierarchy({
    @ContextConfiguration(name="root", classes={RootConfiguration.class, TestDatabaseConfiguration.class}),
    @ContextConfiguration(name="web", classes={WebConfiguration.class, MockMvcConfiguration.class, PermissionAspectIntegrationTest.Fixtures.class})
})
class PermissionAspectIntegrationTest extends SpringWebIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired WebApplicationContext web;
    @Autowired AtomicInteger writes;
    long admin, editor, plain, adminRole, editorRole, categoryRead, publish;
    static final String BASE = "/api/b14-probe";

    @BeforeEach void seed() {
        writes.set(0);
        for (String table : List.of("sys_user_role", "sys_role_permission", "sys_operation_log", "sys_user", "sys_role", "sys_permission"))
            jdbc.update("DELETE FROM " + table);
        admin = user("admin"); editor = user("editor"); plain = user("plain");
        adminRole = role("ADMIN"); editorRole = role("EDITOR");
        categoryRead = permission("cms:category:read"); publish = permission("cms:article:publish");
        long create = permission("cms:article:create");
        link(admin, adminRole); link(editor, editorRole);
        for (long id : List.of(categoryRead, publish, create)) grant(adminRole, id);
        grant(editorRole, categoryRead); grant(editorRole, create);
    }

    @Test void categoryAndArticleProbesApplyTheirOwnPermissionsBeforeBusiness() throws Exception {
        mvc.perform(get(BASE+"/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE+"/categories").session(session(plain)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mvc.perform(get(BASE+"/categories").session(session(editor))).andExpect(status().isOk());
        MockHttpSession e = session(editor), a = session(admin);
        String et = csrf(e), at = csrf(a);
        mvc.perform(post(BASE+"/articles").session(e).header("X-CSRF-Token",et)).andExpect(status().isOk());
        mvc.perform(post(BASE+"/articles/1/publish").session(e).header("X-CSRF-Token",et)).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/articles/1/publish").session(a).header("X-CSRF-Token",at)).andExpect(status().isOk());
        assertThat(writes.get()).isEqualTo(2);
    }

    @Test void neitherMissingPermissionNorMissingCsrfCanCauseSideEffects() throws Exception {
        MockHttpSession p = session(plain), a = session(admin);
        mvc.perform(post(BASE+"/articles").session(p).header("X-CSRF-Token",csrf(p)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mvc.perform(post(BASE+"/articles").session(a)).andExpect(status().isForbidden());
        assertThat(writes.get()).isZero();
    }

    @Test void grantsAndRevocationsTakeEffectOnNextRequestWithoutRelogin() throws Exception {
        MockHttpSession e = session(editor);
        String token = csrf(e);
        mvc.perform(post(BASE+"/articles/1/publish").session(e).header("X-CSRF-Token",token)).andExpect(status().isForbidden());
        grant(editorRole,publish);
        mvc.perform(post(BASE+"/articles/1/publish").session(e).header("X-CSRF-Token",token)).andExpect(status().isOk());
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?",editorRole,publish);
        mvc.perform(post(BASE+"/articles/1/publish").session(e).header("X-CSRF-Token",token)).andExpect(status().isForbidden());
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test void administratorRoleHasNoImplicitPermissionBypass() throws Exception {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=?",adminRole);
        mvc.perform(get(BASE+"/categories").session(session(admin))).andExpect(status().isForbidden());
    }

    @Test void disabledAndDeletedRolesOrPermissionsCannotAuthorize() throws Exception {
        MockHttpSession e = session(editor);
        for (String table : List.of("sys_role","sys_permission")) {
            long id = table.equals("sys_role") ? editorRole : categoryRead;
            jdbc.update("UPDATE "+table+" SET status='DISABLED' WHERE id=?",id);
            mvc.perform(get(BASE+"/categories").session(e)).andExpect(status().isForbidden());
            jdbc.update("UPDATE "+table+" SET status='ENABLED',deleted=1 WHERE id=?",id);
            mvc.perform(get(BASE+"/categories").session(e)).andExpect(status().isForbidden());
            jdbc.update("UPDATE "+table+" SET deleted=0 WHERE id=?",id);
            mvc.perform(get(BASE+"/categories").session(e)).andExpect(status().isOk());
        }
    }

    @Test void disabledAndDeletedAccountsInvalidateSessionsBeforePermissionCheck() throws Exception {
        MockHttpSession e = session(editor);
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",editor);
        mvc.perform(get(BASE+"/categories").session(e)).andExpect(status().isUnauthorized());
        assertThat(e.isInvalid()).isTrue();
        jdbc.update("UPDATE sys_user SET status='ACTIVE',deleted=1 WHERE id=?",editor);
        e = session(editor);
        mvc.perform(get(BASE+"/categories").session(e)).andExpect(status().isUnauthorized());
        assertThat(e.isInvalid()).isTrue();
    }

    @Test void anonymousReadIsExplicitAndAnonymousWritesStillRequireCsrf() throws Exception {
        mvc.perform(get(BASE+"/public/articles")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf-token")).andExpect(status().isOk());
        mvc.perform(post(BASE+"/public/preview")).andExpect(status().isForbidden());
        MockHttpSession anonymous = new MockHttpSession();
        mvc.perform(post(BASE+"/public/preview").session(anonymous).header("X-CSRF-Token",csrf(anonymous)))
                .andExpect(status().isOk());
    }

    @Test void anonymousAnnotationCannotOverrideRequiredPermission() throws Exception {
        mvc.perform(get(BASE+"/conflicting")).andExpect(status().isUnauthorized());
        // AnonymousAccess never establishes a principal, even when a cookie happens to be present.
        mvc.perform(get(BASE+"/conflicting").session(session(admin))).andExpect(status().isUnauthorized());
    }

    @Test void unknownAndBlankPermissionCodesFailClosed() throws Exception {
        for (String path : List.of("/unknown","/blank"))
            mvc.perform(get(BASE+path).session(session(admin))).andExpect(status().isForbidden());
    }

    @Test void deniedResponseKeepsRequestIdAndClearsRequestPrincipal() throws Exception {
        var result = mvc.perform(get(BASE+"/categories").session(session(plain)).header("X-Request-Id","b14-denied"))
                .andExpect(status().isForbidden()).andExpect(header().string("X-Request-Id","b14-denied"))
                .andExpect(jsonPath("$.requestId").value("b14-denied")).andReturn();
        assertThat(result.getRequest().getAttribute(SessionKeys.CURRENT_USER_SNAPSHOT)).isNull();
        assertThat(result.getRequest().getAttribute(SessionKeys.REQUEST_USER)).isNull();
    }

    @Test void springProxyRejectsDirectInvocationWithoutAuthenticatedRequest() {
        var controller = web.getBean(ContentProbe.class);
        assertThat(AopUtils.isAopProxy(controller)).isTrue();
        assertThatThrownBy(controller::categories).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("AUTH_UNAUTHENTICATED"));
    }

    @Test void everyProductionPermissionEndpointIsProxyableAndAspectIsOnlyInMvc() {
        assertThat(web.getBeansOfType(PermissionAspect.class)).hasSize(1);
        assertThat(web.getParent().getBeansOfType(PermissionAspect.class)).isEmpty();
        int protectedEndpoints = 0;
        for (var handler : web.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values()) {
            if (!handler.getBeanType().getName().startsWith("com.zachery.cms.modules.identity")) continue;
            if (!handler.hasMethodAnnotation(RequirePermission.class)) continue;
            protectedEndpoints++;
            var method = handler.getMethod();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(method.getModifiers()) || Modifier.isStatic(method.getModifiers())).isFalse();
            assertThat(Modifier.isFinal(handler.getBeanType().getModifiers())).isFalse();
            assertThat(handler.hasMethodAnnotation(AnonymousAccess.class)).isFalse();
            assertThat(AopUtils.isAopProxy(web.getBean(handler.getBeanType()))).isTrue();
        }
        assertThat(protectedEndpoints).isEqualTo(7); // Includes the B-16 log query endpoint.
    }

    private MockHttpSession session(long id) {
        var session = new MockHttpSession(); session.setAttribute(SessionKeys.CURRENT_USER_ID,id); return session;
    }
    private String csrf(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf-token").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/token").asText();
    }
    private long user(String name) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES (?,'test-only',?)",name,name);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    private long role(String code) {
        jdbc.update("INSERT INTO sys_role(code,name) VALUES (?,?)",code,code);
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?",Long.class,code);
    }
    private long permission(String code) {
        jdbc.update("INSERT INTO sys_permission(code,name) VALUES (?,?)",code,code);
        return jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?",Long.class,code);
    }
    private void link(long user,long role) { jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",user,role,admin); }
    private void grant(long role,long permission) { jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,permission,admin); }

    @Configuration(proxyBeanMethods=false)
    @Import(ContentProbe.class)
    static class Fixtures {
        @Bean AtomicInteger writes() { return new AtomicInteger(); }
    }
    /** Contract probes only: these do not implement A's category/article business. */
    @RestController
    @RequestMapping(BASE)
    public static class ContentProbe {
        private final AtomicInteger writes;
        public ContentProbe(AtomicInteger writes) { this.writes = writes; }
        @GetMapping("/categories") @RequirePermission("cms:category:read")
        public ApiResponse<List<String>> categories() { return ApiResponse.ok(List.of("fixture")); }
        @PostMapping("/articles") @RequirePermission("cms:article:create")
        public ApiResponse<Integer> create() { return ApiResponse.ok(writes.incrementAndGet()); }
        @PostMapping("/articles/{id}/publish") @RequirePermission("cms:article:publish")
        public ApiResponse<Integer> publish() { return ApiResponse.ok(writes.incrementAndGet()); }
        @GetMapping("/public/articles") @AnonymousAccess
        public ApiResponse<List<String>> published() { return ApiResponse.ok(List.of()); }
        @PostMapping("/public/preview") @AnonymousAccess
        public ApiResponse<Void> preview() { return ApiResponse.ok(); }
        @GetMapping("/unknown") @RequirePermission("cms:unknown")
        public ApiResponse<Void> unknown() { return ApiResponse.ok(); }
        @GetMapping("/blank") @RequirePermission("")
        public ApiResponse<Void> blank() { return ApiResponse.ok(); }
        @GetMapping("/conflicting") @AnonymousAccess @RequirePermission("cms:category:read")
        public ApiResponse<Void> conflicting() { return ApiResponse.ok(); }
    }
}
