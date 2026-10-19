package com.zachery.cms.modules.content;

import com.fasterxml.jackson.databind.*;
import com.zachery.cms.config.*;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.modules.identity.auth.service.PasswordHasher;
import com.zachery.cms.security.annotation.*;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.*;
import org.h2.api.Trigger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties="cms.db.url=jdbc:h2:mem:a_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ContextHierarchy({
    @ContextConfiguration(name="root",classes={RootConfiguration.class,ContentTestConfiguration.class}),
    @ContextConfiguration(name="web",classes={WebConfiguration.class,MockMvcConfiguration.class})
})
class ContentHttpIntegrationTest extends SpringWebIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RequestMappingHandlerMapping mappings;
    long editor, reviewer, publisher, plain, editorRole, publisherRole;
    static final String HASH = new PasswordHasher().encode("content-test-password");
    record Actor(MockHttpSession session,String token) {}

    @BeforeEach void seed() {
        HistoryFailure.articleId=0;
        for(String table:List.of("cms_article_status_history","cms_article")) db.update("DELETE FROM "+table);
        db.update("UPDATE cms_category SET parent_id=NULL");db.update("DELETE FROM cms_category");
        for(String table:List.of("sys_operation_log","sys_user_role","sys_role_permission","sys_user","sys_role","sys_permission")) db.update("DELETE FROM "+table);
        editor=user("content-editor"); reviewer=user("content-reviewer"); publisher=user("content-publisher"); plain=user("content-plain");
        editorRole=role("EDITOR",editor,List.of("cms:category:read","cms:category:manage","cms:article:read","cms:article:create","cms:article:edit","cms:article:delete","cms:article:submit"));
        role("REVIEWER",reviewer,List.of("cms:article:read","cms:article:audit"));
        publisherRole=role("PUBLISHER",publisher,List.of("cms:article:read","cms:article:publish","cms:article:offline"));
    }
    @AfterEach void reset() { HistoryFailure.articleId=0;db.execute("DROP TRIGGER IF EXISTS a_http_history_failure"); }

    @Test void everyRealContentRouteDeclaresItsPermissionOrAnonymousBoundaryAndWriteLog() {
        int endpoints=0,writes=0;
        for(var entry:mappings.getHandlerMethods().entrySet()) {
            if(!entry.getValue().getBeanType().getPackageName().startsWith("com.zachery.cms.modules.content")) continue;
            endpoints++;
            var method=entry.getValue();boolean anonymous=method.hasMethodAnnotation(AnonymousAccess.class);
            assertThat(anonymous ^ method.hasMethodAnnotation(RequirePermission.class)).isTrue();
            if(entry.getKey().getMethodsCondition().getMethods().stream().anyMatch(value->!value.name().equals("GET"))) {
                writes++;assertThat(method.hasMethodAnnotation(OperationLog.class)).isTrue();assertThat(anonymous).isFalse();
            }
        }
        assertThat(endpoints).isEqualTo(24);assertThat(writes).isEqualTo(16);
    }

    @Test void categoryRoutesEnforceAuthenticationPermissionCsrfVersionsAndTrustedAudit() throws Exception {
        send(null,"GET","/api/categories/tree",null,401);
        send(actor(plain),"GET","/api/categories/tree",null,403);
        var e=actor(editor);
        mvc.perform(post("/api/categories").session(e.session()).contentType("application/json").content("{\"name\":\"missing csrf\"}"))
                .andExpect(status().isForbidden());
        assertThat(count("cms_category")).isZero();
        long id=createCategory(e);
        assertThat(db.queryForObject("SELECT operator_id FROM sys_operation_log WHERE action='CATEGORY_CREATE' AND result='SUCCESS'",Long.class)).isEqualTo(editor);
        assertThat(db.queryForObject("SELECT target_id FROM sys_operation_log WHERE action='CATEGORY_CREATE' AND result='SUCCESS'",Long.class)).isEqualTo(id);
        send(e,"PATCH","/api/categories/"+id+"/sort",Map.of("sortOrder",10,"version",0),200);
        send(e,"PUT","/api/categories/"+id,Map.of("name","stale","version",0),409);
        send(e,"PATCH","/api/categories/"+id+"/status",Map.of("status","DISABLED","version",1),200);
        send(e,"PATCH","/api/categories/"+id+"/status",Map.of("status","ENABLED","version",2),200);
        send(e,"DELETE","/api/categories/"+id+"?version=3",null,200);
        send(e,"GET","/api/categories/"+id,null,404);
        assertThat(send(e,"GET","/api/categories/tree",null,200).path("data")).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='CATEGORY_UPDATE' AND result='SUCCESS'",Long.class)).isZero();
    }

    @Test void realRoleWorkflowRejectsUnauthorizedAuditAndHidesOfflinePublicContent() throws Exception {
        var e=actor(editor);var r=actor(reviewer);var p=actor(publisher);
        long category=createCategory(e), id=createArticle(e,category);
        send(e,"POST","/api/articles/"+id+"/submit",Map.of("version",0),200);
        send(e,"POST","/api/articles/"+id+"/approve",Map.of("version",1),403);
        assertThat(send(r,"GET","/api/articles/review-queue",null,200).at("/data/total").asLong()).isEqualTo(1);
        send(r,"POST","/api/articles/"+id+"/reject",Map.of("version",1,"comment"," "),400);
        send(r,"POST","/api/articles/"+id+"/reject",Map.of("version",1,"comment","补充资料"),200);
        send(e,"PUT","/api/articles/"+id,Map.of("title","修订文章","content","<p>安全正文</p>","categoryId",category,"version",2),200);
        send(e,"POST","/api/articles/"+id+"/submit",Map.of("version",3),200);
        send(r,"POST","/api/articles/"+id+"/approve",Map.of("version",4,"comment","内部审核意见"),200);
        send(p,"POST","/api/articles/"+id+"/publish",Map.of("version",5),200);
        var publicData=send(null,"GET","/api/public/articles/"+id,null,200).path("data");
        assertThat(publicData.has("version")).isFalse();assertThat(publicData.has("reviewComment")).isFalse();assertThat(publicData.has("reviewerId")).isFalse();
        assertThat(send(null,"GET","/api/public/articles",null,200).at("/data/total").asLong()).isEqualTo(1);
        send(p,"POST","/api/articles/"+id+"/offline",Map.of("version",6),200);
        send(null,"GET","/api/public/articles/"+id,null,404);
        assertThat(send(e,"GET","/api/articles/"+id+"/history",null,200).at("/data/total").asLong()).isEqualTo(7);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='ARTICLE_APPROVE' AND result='SUCCESS'",Long.class)).isEqualTo(1);
    }

    @Test void requestBindingValidatesPagesAndIgnoresClientIdentityAndStateFields() throws Exception {
        var e=actor(editor);long category=createCategory(e);
        var response=send(e,"POST","/api/articles",Map.of("title","安全内容","content","<p>正文</p><script>alert(1)</script>","categoryId",category,"authorId",plain,"status","PUBLISHED","operatorId",plain),200).path("data");
        assertThat(response.path("authorId").asLong()).isEqualTo(editor);assertThat(response.path("status").asText()).isEqualTo("DRAFT");
        assertThat(response.path("content").asText()).doesNotContain("script");
        for(String query:List.of("page=0","size=101","sort=secret","direction=OTHER","createdFrom=2026-10-30T00:00:00")) send(e,"GET","/api/articles?"+query,null,400);
        var page=send(e,"GET","/api/articles?page=1&size=10&status=DRAFT",null,200).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("records").get(0).has("content")).isFalse();
        long id=response.path("id").asLong();
        var edit=Map.of("title","修改内容","content","<p>修订正文</p>","categoryId",category,"version",0);
        send(e,"PUT","/api/articles/"+id,edit,200);
        send(e,"PUT","/api/articles/"+id,edit,409);
        send(e,"DELETE","/api/articles/"+id+"?version=0",null,409);
        send(e,"DELETE","/api/articles/"+id+"?version=1",null,200);
        send(e,"GET","/api/articles/"+id,null,404);
    }

    @Test void batchStructuredConflictsAndHistoryFaultRollbackWithoutFalseSuccessLogs() throws Exception {
        var e=actor(editor);var r=actor(reviewer);var p=actor(publisher);long category=createCategory(e);
        long first=approved(e,r,category),second=createArticle(e,category);
        var mixed=send(p,"POST","/api/articles/batch-publish",Map.of("items",List.of(Map.of("id",first,"version",2),Map.of("id",second,"version",0))),409);
        assertThat(mixed.at("/data/failures/0/id").asLong()).isEqualTo(second);
        assertThat(db.queryForObject("SELECT status FROM cms_article WHERE id=?",String.class,first)).isEqualTo("APPROVED");
        send(e,"POST","/api/articles/"+second+"/submit",Map.of("version",0),200);send(r,"POST","/api/articles/"+second+"/approve",Map.of("version",1),200);
        db.execute("CREATE TRIGGER a_http_history_failure BEFORE INSERT ON cms_article_status_history FOR EACH ROW CALL '"+HistoryFailure.class.getName()+"'");
        HistoryFailure.articleId=second;
        var request=Map.of("items",List.of(Map.of("id",second,"version",2),Map.of("id",first,"version",2)));
        var failed=send(p,"POST","/api/articles/batch-publish",request,409);HistoryFailure.articleId=0;
        assertThat(failed.at("/data/failures/0/code").asText()).isEqualTo("ARTICLE_BATCH_WRITE_FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE status='APPROVED' AND version=2",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history WHERE action='PUBLISH'",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='ARTICLE_BATCH_PUBLISH' AND result='SUCCESS'",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='ARTICLE_BATCH_PUBLISH' AND result='FAILURE'",Long.class)).isEqualTo(2);
        var success=send(p,"POST","/api/articles/batch-publish",request,200).path("data");
        assertThat(success.path("count").asInt()).isEqualTo(2);assertThat(success.at("/items/0/id").asLong()).isEqualTo(second);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history WHERE action='PUBLISH'",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action='ARTICLE_BATCH_PUBLISH' AND result='SUCCESS'",Long.class)).isEqualTo(1);
    }

    @Test void revokedPermissionsAndDisabledAccountsTakeEffectOnExistingSession() throws Exception {
        var e=actor(editor);var r=actor(reviewer);var p=actor(publisher);long category=createCategory(e),id=approved(e,r,category);
        db.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='cms:article:publish')",publisherRole);
        send(p,"POST","/api/articles/"+id+"/publish",Map.of("version",2),403);
        assertThat(db.queryForObject("SELECT status FROM cms_article WHERE id=?",String.class,id)).isEqualTo("APPROVED");
        db.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",publisher);
        send(p,"GET","/api/articles",null,401);assertThat(p.session().isInvalid()).isTrue();
    }

    Actor actor(long id) throws Exception { var session=new MockHttpSession();session.setAttribute(SessionKeys.CURRENT_USER_ID,id);
        return new Actor(session,json.readTree(mvc.perform(get("/api/auth/csrf-token").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/token").asText()); }
    JsonNode send(Actor actor,String method,String path,Object body,int status) throws Exception {
        var request=request(HttpMethod.valueOf(method),path);if(actor!=null) {request.session(actor.session());if(!method.equals("GET")) request.header("X-CSRF-Token",actor.token());}
        if(body!=null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn().getResponse().getContentAsByteArray());
    }
    long createCategory(Actor e) throws Exception {return send(e,"POST","/api/categories",Map.of("name","HTTP分类","sortOrder",0),200).at("/data/id").asLong();}
    long createArticle(Actor e,long category) throws Exception {return send(e,"POST","/api/articles",Map.of("title","HTTP文章","content","<p>内容</p>","categoryId",category),200).at("/data/id").asLong();}
    long approved(Actor e,Actor r,long category) throws Exception {long id=createArticle(e,category);send(e,"POST","/api/articles/"+id+"/submit",Map.of("version",0),200);send(r,"POST","/api/articles/"+id+"/approve",Map.of("version",1),200);return id;}
    long count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    long user(String name) {db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES (?,?,?)",name,HASH,name);return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);}
    long role(String code,long user,List<String> permissions) {
        db.update("INSERT INTO sys_role(code,name) VALUES (?,?)",code,code);long id=db.queryForObject("SELECT id FROM sys_role WHERE code=?",Long.class,code);
        db.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",user,id,user);
        for(String permission:permissions) {db.update("MERGE INTO sys_permission(code,name) KEY(code) VALUES (?,?)",permission,permission);long pid=db.queryForObject("SELECT id FROM sys_permission WHERE code=?",Long.class,permission);db.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",id,pid,user);}
        return id;
    }
    public static class HistoryFailure implements Trigger {
        static volatile long articleId;
        @Override public void fire(Connection connection,Object[] oldRow,Object[] row) throws SQLException {
            if(articleId!=0&&((Number)row[1]).longValue()==articleId&&"PUBLISH".equals(row[3])) throw new SQLException("synthetic history failure");
        }
    }
}
