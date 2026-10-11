package com.zachery.cms.modules.identity.user;

import com.fasterxml.jackson.databind.*;
import com.zachery.cms.modules.identity.auth.service.PasswordHasher;
import com.zachery.cms.modules.identity.persistence.entity.OperationLogEntity;
import com.zachery.cms.modules.identity.persistence.mapper.RbacAssignmentMapper;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.*;
import com.zachery.cms.config.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties="cms.db.url=jdbc:h2:mem:useradmin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ContextHierarchy({
    @ContextConfiguration(name="root",classes={RootConfiguration.class,TestDatabaseConfiguration.class,UserAdministrationIntegrationTest.Fixtures.class}),
    @ContextConfiguration(name="web",classes={WebConfiguration.class,MockMvcConfiguration.class})
})
class UserAdministrationIntegrationTest extends SpringWebIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordHasher passwords;
    @Autowired Probe probe;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RbacAssignmentMapper assignments;
    long admin, plain, target, disabled, deleted, adminRole, limitedRole, read, manage, assign, permissionManage;
    record Client(MockHttpSession session,String csrf) {}

    @BeforeEach void seed() {
        probe.reset();
        for(String table:List.of("sys_user_role","sys_role_permission","sys_operation_log"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE sys_user SET updated_by=NULL,created_by=NULL");
        for(String table:List.of("sys_user","sys_role","sys_permission")) jdbc.update("DELETE FROM "+table);
        admin=user("manager","ACTIVE",0); plain=user("plain","ACTIVE",0);
        target=user("target_user","ACTIVE",0); disabled=user("target_off","DISABLED",0);
        deleted=user("deleted","ACTIVE",1);
        adminRole=role("ADMIN"); limitedRole=role("LIMITED");
        read=permission("cms:user:read"); manage=permission("cms:user:manage");
        assign=permission("cms:role:assign"); permissionManage=permission("cms:permission:manage");
        link(admin,adminRole);
        for(long id:List.of(read,manage,assign,permissionManage)) grant(adminRole,id);
        jdbc.update("INSERT INTO sys_operation_log(operator_id,operator_name,module,action,result,request_id) VALUES (?,'target_user','USER','EXISTING','SUCCESS','history-fixture')",target);
    }
    @AfterEach void reset() { probe.reset(); }

    @Test void listUsesStablePaginationAndSafeProjection() throws Exception {
        JsonNode data=list(Map.of());
        assertThat(data.path("total").asLong()).isEqualTo(4);
        assertThat(data.path("page").asLong()).isEqualTo(1);
        assertThat(data.path("size").asLong()).isEqualTo(20);
        long[] ids={disabled,target,plain,admin};
        for(int i=0;i<4;i++) {
            JsonNode row=list(Map.of("size","1","page",""+(i+1))).path("records").get(0);
            assertThat(row.path("id").asLong()).isEqualTo(ids[i]);
            assertThat(row.path("createdAt").asText()).isEqualTo("2026-10-11T01:00:00.123Z");
            assertThat(row.toString()).doesNotContain("password","hash-fixture","deleted","createdBy");
        }
        assertThat(list(Map.of("page","5","size","1")).path("records").size()).isZero();
        assertThat(list(Map.of("username","not_found")).path("total").asLong()).isZero();
    }
    @Test void combinesLiteralSubstringStatusAndUtcTimeFilters() throws Exception {
        JsonNode data=list(Map.of("username","get_","status","ACTIVE","from","2026-10-11T09:00:00.123+08:00","to","2026-10-11T01:00:00.123Z"));
        assertThat(data.path("total").asLong()).isEqualTo(1);
        assertThat(data.path("records").get(0).path("id").asLong()).isEqualTo(target);
        assertThat(list(Map.of("username","target")).path("total").asLong()).isEqualTo(2);
        assertThat(list(Map.of("status","DISABLED")).path("total").asLong()).isEqualTo(1);
        assertThat(list(Map.of("from","2026-10-11T01:00:00.124Z")).path("total").asLong()).isZero();
        for(String value:List.of("%","!","' OR 1=1 --"))
            assertThat(list(Map.of("username",value)).path("total").asLong()).isZero();
    }
    @Test void invalidQueryParametersReturn400() throws Exception {
        for(var params:List.of(Map.of("size","101"),Map.of("page","0"),Map.of("page","9223372036854775807","size","100"),
                Map.of("username"," "),Map.of("username","x".repeat(65)),Map.of("status","DELETED"),Map.of("sort","id:asc"),
                Map.of("from","2026-10-11T00:00:00"),Map.of("from","2026-10-11T00:00:00.0001Z"),
                Map.of("from","2026-10-12T00:00:00Z","to","2026-10-11T00:00:00Z"))) {
            var request=get("/api/users").session(client(admin).session());
            params.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errors").isArray());
        }
        mvc.perform(get("/api/users").session(client(admin).session()).param("status","ACTIVE","DISABLED")).andExpect(status().isBadRequest());
    }
    @Test void separatesReadManagePermissionsAndRejectsAnonymousAndCsrf() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users").session(client(plain).session())).andExpect(status().isForbidden());
        write(client(plain),target,"DISABLED",0).andExpect(status().isForbidden());
        link(plain,limitedRole); grant(limitedRole,read);
        mvc.perform(get("/api/users").session(client(plain).session())).andExpect(status().isOk());
        write(client(plain),target,"DISABLED",0).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=?",limitedRole); grant(limitedRole,manage);
        mvc.perform(get("/api/users").session(client(plain).session())).andExpect(status().isForbidden());
        write(client(plain),target,"DISABLED",0).andExpect(status().isOk());
        mvc.perform(put(path(disabled)).session(client(admin).session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\",\"version\":0}")).andExpect(status().isForbidden());
    }
    @Test void disablingAndEnablingUpdatesVersionAuditAndPreservesRelations() throws Exception {
        link(target,limitedRole);
        Client manager=client(admin);
        write(manager,target,"DISABLED",0).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject("SELECT updated_by FROM sys_user WHERE id=?",Long.class,target)).isEqualTo(admin);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Long.class,target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE request_id='history-fixture'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='SET_STATUS' AND result='SUCCESS' AND operator_id=? AND target_id=?",Long.class,admin,target)).isEqualTo(1);
        write(manager,target,"ACTIVE",1).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        assertThat(state(target)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?",String.class,target)).isEqualTo("hash-fixture");
    }
    @Test void disabledUserCannotLoginAndExistingSessionExpires() throws Exception {
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?",passwords.encode("FixturePass123!"),target);
        Client old=client(target);
        write(client(admin),target,"DISABLED",0).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(old.session())).andExpect(status().isUnauthorized());
        assertThat(old.session().isInvalid()).isTrue();
        login("target_user","FixturePass123!").andExpect(status().isUnauthorized());
        write(client(admin),target,"ACTIVE",1).andExpect(status().isOk());
        login("target_user","FixturePass123!").andExpect(status().isOk());
    }
    @Test void selfDisableAndDisablingLastRbacAdministratorAreRejected() throws Exception {
        write(client(admin),admin,"DISABLED",0).andExpect(status().isConflict());
        link(plain,limitedRole); grant(limitedRole,manage);
        write(client(plain),admin,"DISABLED",0).andExpect(status().isConflict());
        assertThat(state(admin)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT version FROM sys_user WHERE id=?",Integer.class,admin)).isZero();
        assertThat(assignments.hasRbacAdministrator()).isTrue();
    }
    @Test void canDisableAnotherAdministratorWhenAnEffectiveAdministratorRemains() throws Exception {
        link(target,adminRole);
        write(client(admin),target,"DISABLED",0).andExpect(status().isOk());
        assertThat(assignments.hasRbacAdministrator()).isTrue();
    }
    @Test void missingDeletedAndInvalidTargetsAndPayloadsAreRejected() throws Exception {
        Client manager=client(admin);
        write(manager,Long.MAX_VALUE,"DISABLED",0).andExpect(status().isNotFound());
        write(manager,deleted,"DISABLED",0).andExpect(status().isNotFound());
        write(manager,0,"DISABLED",0).andExpect(status().isBadRequest());
        for(Object body:List.of(Map.of("status","ACTIVE"),Map.of("version",0),Map.of("status","BAD","version",0),
                Map.of("status","DISABLED","version",-1))) {
            raw(manager,path(target),body).andExpect(status().isBadRequest());
        }
    }
    @Test void staleVersionConflictsAndSameStateDoesNotIncrementVersion() throws Exception {
        Client manager=client(admin);
        write(manager,target,"ACTIVE",0).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        write(manager,target,"DISABLED",0).andExpect(status().isOk());
        write(manager,target,"ACTIVE",0).andExpect(status().isConflict());
        assertThat(state(target)).isEqualTo("DISABLED");
    }
    @Test void clientCannotOverrideIdentityPasswordOrRoleFields() throws Exception {
        raw(client(admin),path(target),Map.of("status","DISABLED","version",0,"updatedBy",plain,
                "username","hacked","passwordHash","hacked","roleIds",List.of(adminRole),"deleted",1))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT updated_by FROM sys_user WHERE id=?",Long.class,target)).isEqualTo(admin);
        assertThat(jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?",String.class,target)).isEqualTo("target_user");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Long.class,target)).isZero();
    }
    @Test void successAuditFailureRollsBackStatusVersionAndAuditFill() throws Exception {
        probe.failAudit=true;
        write(client(admin),target,"DISABLED",0).andExpect(status().isInternalServerError());
        assertThat(state(target)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT version FROM sys_user WHERE id=?",Integer.class,target)).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_by FROM sys_user WHERE id=?",Long.class,target)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='SET_STATUS' AND result='SUCCESS'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='SET_STATUS' AND result='FAILURE'",Long.class)).isEqualTo(1);
    }
    @Test void concurrentStatusUpdatesWithSameVersionCannotOverwriteEachOther() throws Exception {
        Client a=client(admin),b=client(admin);
        assertThat(concurrently(()->write(a,target,"DISABLED",0).andReturn().getResponse().getStatus(),
                ()->write(b,target,"DISABLED",0).andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT version FROM sys_user WHERE id=?",Integer.class,target)).isEqualTo(1);
    }
    @Test void concurrentStatusAndRoleRemovalPreserveLastAdministrator() throws Exception {
        link(target,adminRole); link(plain,limitedRole); grant(limitedRole,manage); grant(limitedRole,assign);
        Client a=client(plain),b=client(plain);
        assertThat(concurrently(()->write(a,admin,"DISABLED",0).andReturn().getResponse().getStatus(),
                ()->raw(b,"/api/users/"+target+"/roles",Map.of("roleIds",List.of())).andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200,409);
        assertThat(assignments.hasRbacAdministrator()).isTrue();
    }
    @Test void authorityRevokedDuringLockWaitIsRechecked() throws Exception {
        link(plain,limitedRole); grant(limitedRole,manage);
        Client limited=client(plain);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            probe.beforeLock=new CountDownLatch(1);
            Future<Integer> response=new TransactionTemplate(transactions).execute(tx->{
                jdbc.queryForObject("SELECT id FROM sys_permission WHERE id=? FOR UPDATE",Long.class,assign);
                Future<Integer> future=pool.submit(()->write(limited,target,"DISABLED",0).andReturn().getResponse().getStatus());
                try { assertThat(probe.beforeLock.await(5,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=?",limitedRole,manage);
                return future;
            });
            assertThat(response.get(15,TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(state(target)).isEqualTo("ACTIVE");
        } finally { pool.shutdownNow(); }
    }

    private JsonNode list(Map<String,String> params) throws Exception {
        var request=get("/api/users").session(client(admin).session()); params.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private ResultActions login(String username,String password) throws Exception {
        var session=new MockHttpSession();
        var token=mvc.perform(get("/api/auth/csrf-token").session(session)).andReturn();
        String csrf=json.readTree(token.getResponse().getContentAsString()).at("/data/token").asText();
        return mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token",csrf)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password))));
    }
    private Client client(long id) throws Exception {
        var session=new MockHttpSession(null,"b11-"+UUID.randomUUID()); session.setAttribute(SessionKeys.CURRENT_USER_ID,id);
        var result=mvc.perform(get("/api/auth/csrf-token").session(session)).andReturn();
        return new Client(session,json.readTree(result.getResponse().getContentAsString()).at("/data/token").asText());
    }
    private String path(long id) { return "/api/users/"+id+"/status"; }
    private ResultActions write(Client client,long id,String status,int version) throws Exception { return raw(client,path(id),Map.of("status",status,"version",version)); }
    private ResultActions raw(Client client,String path,Object body) throws Exception {
        return mvc.perform(put(path).session(client.session()).header("X-CSRF-Token",client.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?",String.class,id); }
    private long user(String name,String status,int deleted) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status,deleted,created_at) VALUES (?,'hash-fixture',?,?,?,'2026-10-11 01:00:00.123')",name,name,status,deleted);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    private long role(String code) { jdbc.update("INSERT INTO sys_role(code,name) VALUES (?,?)",code,code); return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?",Long.class,code); }
    private long permission(String code) { jdbc.update("INSERT INTO sys_permission(code,name) VALUES (?,?)",code,code); return jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?",Long.class,code); }
    private void grant(long role,long permission) { jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,permission,admin); }
    private void link(long user,long role) { jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",user,role,admin); }
    private List<Integer> concurrently(Callable<Integer> a,Callable<Integer> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> first=pool.submit(()->{start.await();return a.call();});
            Future<Integer> second=pool.submit(()->{start.await();return b.call();});
            start.countDown(); return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Configuration(proxyBeanMethods=false)
    static class Fixtures { @Bean Probe userAdministrationProbe() { return new Probe(); } }
    @Intercepts({
        @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
    })
    public static class Probe implements Interceptor {
        volatile CountDownLatch beforeLock;
        volatile boolean failAudit;
        void reset() { beforeLock=null;failAudit=false; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.endsWith("RbacAssignmentMapper.lockManagementPermission") && beforeLock!=null) beforeLock.countDown();
            Object result=invocation.proceed();
            if(failAudit && id.endsWith("OperationLogMapper.insert")
                    && invocation.getArgs()[1] instanceof OperationLogEntity log && "SUCCESS".equals(log.getResult()))
                throw new IllegalStateException("Synthetic audit failure");
            return result;
        }
    }
}
