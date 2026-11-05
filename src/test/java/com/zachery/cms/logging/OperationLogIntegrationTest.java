package com.zachery.cms.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.config.*;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.modules.identity.persistence.entity.OperationLogEntity;
import com.zachery.cms.security.annotation.RequirePermission;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties="cms.db.url=jdbc:h2:mem:b15_audit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ContextHierarchy({
    @ContextConfiguration(name="root", classes={RootConfiguration.class, TestDatabaseConfiguration.class, OperationLogIntegrationTest.Fixtures.class}),
    @ContextConfiguration(name="web", classes={WebConfiguration.class, MockMvcConfiguration.class, OperationLogIntegrationTest.ProbeController.class})
})
public class OperationLogIntegrationTest extends SpringWebIntegrationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProbeService business;
    @Autowired AuditFailureProbe failures;
    @Autowired PlatformTransactionManager manager;
    protected long actor, target;
    long role;
    static final String SECRET = "DoNotLog-password-Cookie-token-body";
    static final String HASH = "{pbkdf2-sha256}600000$AAECAwQFBgcICQoLDA0ODw==$wpcF9hlr08mvG4XuvsbKoBoWwAQhRgzErA9WLtQ1Tx8=";

    @BeforeEach void seed() {
        failures.reset();
        for (String table : List.of("sys_operation_log","sys_user_role","sys_role_permission","sys_user","sys_role","sys_permission")) jdbc.update("DELETE FROM "+table);
        actor = user("audit-admin"); target = user("audit-target");
        jdbc.update("INSERT INTO sys_role(code,name) VALUES ('ADMIN','Admin')");
        role = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN'",Long.class);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",actor,role,actor);
        for (String code : List.of("cms:role:assign","cms:permission:manage","cms:article:edit")) {
            jdbc.update("INSERT INTO sys_permission(code,name) VALUES (?,?)",code,code);
            long id = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?",Long.class,code);
            jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,id,actor);
        }
    }
    @AfterEach void reset() { failures.reset(); }

    @Test void realRoleAssignmentStoresTrustedActorTargetAndSafeMetadata() throws Exception {
        var session = session(actor);
        mvc.perform(put("/api/users/"+target+"/roles").session(session).header("X-CSRF-Token",csrf(session))
                .header("X-Request-Id","b15-role-write").header("X-Forwarded-For","8.8.8.8")
                .contentType("application/json").content("{\"roleIds\":[],\"createdBy\":999,\"password\":\""+SECRET+"\"}"))
                .andExpect(status().isOk());
        var row = singleLog();
        assertThat(number(row,"operator_id")).isEqualTo(actor);
        assertThat(row.get("operator_name")).isEqualTo("audit-admin");
        assertThat(number(row,"target_id")).isEqualTo(target);
        assertThat(row).containsEntry("module","USER").containsEntry("action","ASSIGN_ROLES")
                .containsEntry("result","SUCCESS").containsEntry("request_id","b15-role-write").containsEntry("ip_address","127.0.0.1");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.toString()).doesNotContain(SECRET,HASH,session.getId());
    }

    @Test void businessFailureRollsBackAndPersistsOnlyFailure() throws Exception {
        invoke("fail").andExpect(status().isConflict());
        assertThat(display()).isEqualTo("audit-target");
        assertThat(singleLog()).containsEntry("result","FAILURE").containsEntry("error_code","COMMON_CONFLICT");
        assertThat(number(singleLog(),"target_id")).isEqualTo(target);
        assertThat(singleLog().toString()).doesNotContain(SECRET);
    }

    @Test void failedSuccessLogRollsBackBusinessAndRecordsFailureSeparately() throws Exception {
        failures.failSuccessOnce.set(true);
        invoke("success").andExpect(status().isInternalServerError());
        assertThat(display()).isEqualTo("audit-target");
        assertThat(singleLog()).containsEntry("result","FAILURE").containsEntry("error_code","AUDIT_WRITE_FAILED");
    }

    @Test void laterOuterRollbackCannotLeaveSuccess() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            business.success(target);
            assertThat(singleLog()).containsEntry("result","SUCCESS");
            status.setRollbackOnly();
        });
        assertThat(display()).isEqualTo("audit-target");
        assertThat(singleLog()).containsEntry("result","FAILURE").containsEntry("error_code","AUDIT_TRANSACTION_ROLLED_BACK");
    }

    @Test void commitPhaseFailureRollsBackBusinessAndSuccessLog() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            business.success(target);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException(SECRET); }
            });
        })).isInstanceOf(IllegalStateException.class);
        assertThat(display()).isEqualTo("audit-target");
        assertThat(singleLog()).containsEntry("result","FAILURE");
    }

    @Test void requiresNewBusinessAndItsLogSurviveUnrelatedOuterRollback() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            business.independent(target);
            status.setRollbackOnly();
        });
        assertThat(display()).isEqualTo("changed");
        assertThat(singleLog()).containsEntry("result","SUCCESS");
    }

    @Test void checkedExceptionAlsoRollsBackAndIsNotReplaced() {
        assertThatThrownBy(() -> business.checked(target)).isInstanceOf(Exception.class).hasMessage(SECRET);
        assertThat(display()).isEqualTo("audit-target");
        assertThat(singleLog()).containsEntry("result","FAILURE").containsEntry("error_code","COMMON_INTERNAL_ERROR");
    }

    @Test void createdTargetComesFromResultOnlyOnCommit() {
        Created created = business.create("new-audit-user");
        assertThat(number(singleLog(),"target_id")).isEqualTo(created.id());
        assertThat(singleLog()).containsEntry("result","SUCCESS");
    }

    @Test void rolledBackCreationDoesNotKeepAnUncommittedResultId() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            business.create("rolled-back-user"); status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='rolled-back-user'",Integer.class)).isZero();
        assertThat(singleLog()).containsEntry("result","FAILURE").containsEntry("target_id",null);
    }

    @Test void unresolvedTargetIsNullAndCannotBreakBusiness() {
        business.unresolved(target);
        assertThat(display()).isEqualTo("changed");
        assertThat(singleLog()).containsEntry("result","SUCCESS").containsEntry("target_id",null);
    }

    @Test void independentAuditFailureDoesNotReplaceOriginalBusinessFailure() {
        failures.failAll.set(true);
        assertThatThrownBy(() -> business.fail(target)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("COMMON_CONFLICT"));
        assertThat(display()).isEqualTo("audit-target");
        assertThat(logs()).isEmpty();
    }

    @Test void unannotatedQueriesAndPreControllerRejectionsDoNotCreateBusinessLogs() throws Exception {
        mvc.perform(get("/api/roles").session(session(actor))).andExpect(status().isOk());
        mvc.perform(post("/api/audit-probe/success/"+target)).andExpect(status().isUnauthorized());
        var plain = session(target);
        mvc.perform(post("/api/audit-probe/success/"+target).session(plain).header("X-CSRF-Token",csrf(plain)))
                .andExpect(status().isForbidden());
        assertThat(logs()).isEmpty();
    }

    @Test void authenticationLogsUseVerifiedIdentityAndNeverCredentials() throws Exception {
        var session = new MockHttpSession(null,"b15-session-"+UUID.randomUUID());
        String originalSessionId = session.getId();
        String token = csrf(session);
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token",token)
                .contentType("application/json").content("{\"username\":\"audit-admin\",\"password\":\"WrongPass123!\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(singleLog()).containsEntry("operator_id",null).containsEntry("result","FAILURE");
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token",token)
                .contentType("application/json").content("{\"username\":\"audit-admin\",\"password\":\"FixturePass123!\"}"))
                .andExpect(status().isOk());
        token = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token",token)).andExpect(status().isOk());
        assertThat(logs()).hasSize(3);
        assertThat(number(logs().get(1),"operator_id")).isEqualTo(actor);
        assertThat(number(logs().get(2),"operator_id")).isEqualTo(actor);
        assertThat(logs().toString()).doesNotContain(HASH,"WrongPass123!","FixturePass123!",token,originalSessionId);
    }

    @Test void sessionLoginStillWorksIfItsIndependentAuditStoreIsUnavailable() throws Exception {
        failures.failAll.set(true);
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token",csrf(session))
                .contentType("application/json").content("{\"username\":\"audit-admin\",\"password\":\"FixturePass123!\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        assertThat(logs()).isEmpty();
    }

    @Test void annotationOnMvcControllerUsesAtomicBoundaryToo() throws Exception {
        var session = session(actor);
        mvc.perform(post("/api/audit-probe/controller/"+target).session(session).header("X-CSRF-Token",csrf(session)))
                .andExpect(status().isOk());
        assertThat(singleLog()).containsEntry("module","CATEGORY").containsEntry("action","UPDATE");
    }

    private org.springframework.test.web.servlet.ResultActions invoke(String action) throws Exception {
        var session = session(actor);
        return mvc.perform(post("/api/audit-probe/"+action+"/"+target).session(session).header("X-CSRF-Token",csrf(session)));
    }
    protected List<Map<String,Object>> logs() { return jdbc.queryForList("SELECT * FROM sys_operation_log ORDER BY id"); }
    private Map<String,Object> singleLog() { assertThat(logs()).hasSize(1); return logs().get(0); }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private String display() { return jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?",String.class,target); }
    private long user(String name) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES (?,?,?)",name,HASH,name);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    private MockHttpSession session(long id) { var s=new MockHttpSession(null,"b15-session-"+UUID.randomUUID()); s.setAttribute(SessionKeys.CURRENT_USER_ID,id); return s; }
    private String csrf(MockHttpSession session) throws Exception {
        var r=mvc.perform(get("/api/auth/csrf-token").session(session)).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).at("/data/token").asText();
    }

    @Configuration(proxyBeanMethods=false)
    @Import(ProbeService.class)
    public static class Fixtures { @Bean AuditFailureProbe auditFailureProbe() { return new AuditFailureProbe(); } }
    public record Created(Long id) {}
    @Transactional
    public static class ProbeService {
        private final JdbcTemplate jdbc;
        public ProbeService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        @OperationLog(module="ARTICLE",action="UPDATE",targetType="ARTICLE",targetIdExpression="#articleId")
        public void success(long articleId) { change(articleId); }
        @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        @OperationLog(module="ARTICLE",action="UPDATE",targetType="ARTICLE",targetIdExpression="#articleId")
        public void independent(long articleId) { change(articleId); }
        @OperationLog(module="ARTICLE",action="UPDATE",targetType="ARTICLE",targetIdExpression="#articleId")
        public void fail(long articleId) { change(articleId); throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,"COMMON_CONFLICT",SECRET); }
        @OperationLog(module="ARTICLE",action="UPDATE",targetType="ARTICLE",targetIdExpression="#articleId")
        public void checked(long articleId) throws Exception { change(articleId); throw new Exception(SECRET); }
        @OperationLog(module="ARTICLE",action="CREATE",targetType="ARTICLE",targetIdExpression="#result.id")
        public Created create(String username) {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES (?,'test-only',?)",username,username);
            return new Created(jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username));
        }
        @OperationLog(module="ARTICLE",action="UPDATE",targetType="ARTICLE",targetIdExpression="#missingId")
        public void unresolved(long articleId) { change(articleId); }
        public void change(long id) { jdbc.update("UPDATE sys_user SET display_name='changed' WHERE id=?",id); }
    }
    @RestController
    @RequestMapping("/api/audit-probe")
    public static class ProbeController {
        private final ProbeService service;
        public ProbeController(ProbeService service) { this.service=service; }
        @PostMapping("/success/{id}") @RequirePermission("cms:article:edit")
        public ApiResponse<Void> success(@PathVariable long id) { service.success(id); return ApiResponse.ok(); }
        @PostMapping("/fail/{id}") @RequirePermission("cms:article:edit")
        public ApiResponse<Void> fail(@PathVariable long id) { service.fail(id); return ApiResponse.ok(); }
        @PostMapping("/controller/{id}") @RequirePermission("cms:article:edit")
        @OperationLog(module="CATEGORY",action="UPDATE",targetType="CATEGORY",targetIdExpression="#id")
        public ApiResponse<Void> controller(@PathVariable long id) { service.change(id); return ApiResponse.ok(); }
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    public static class AuditFailureProbe implements Interceptor {
        final AtomicBoolean failSuccessOnce = new AtomicBoolean(), failAll = new AtomicBoolean();
        void reset() { failSuccessOnce.set(false); failAll.set(false); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement)invocation.getArgs()[0]).getId().endsWith("OperationLogMapper.insert")) {
                Object argument=invocation.getArgs()[1];
                OperationLogEntity row = argument instanceof OperationLogEntity e ? e :
                        ((Map<?,?>)argument).values().stream().filter(OperationLogEntity.class::isInstance).map(OperationLogEntity.class::cast).findFirst().orElseThrow();
                if (failAll.get() || ("SUCCESS".equals(row.getResult()) && failSuccessOnce.compareAndSet(true,false)))
                    throw new IllegalStateException(SECRET);
            }
            return invocation.proceed();
        }
    }
}
