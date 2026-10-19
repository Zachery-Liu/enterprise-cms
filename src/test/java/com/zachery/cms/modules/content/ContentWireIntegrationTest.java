package com.zachery.cms.modules.content;

import com.fasterxml.jackson.databind.*;
import com.zachery.cms.config.*;
import com.zachery.cms.modules.identity.auth.service.PasswordHasher;
import com.zachery.cms.support.ContentTestConfiguration;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.support.WebApplicationContextUtils;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

/** Actual loopback HTTP + production initializer/security/logging; H2, not external MySQL acceptance. */
public class ContentWireIntegrationTest {
    static final String PASSWORD="Content-Wire-Password";
    static final ObjectMapper JSON=new ObjectMapper();

    public static class Harness implements AutoCloseable {
        final Tomcat server=new Tomcat();
        public final JdbcTemplate db;
        public final String base;
        Harness() throws Exception {
            server.setBaseDir(Files.createTempDirectory(Path.of("target"),"content-wire-").toAbsolutePath().toString());
            server.setPort(0);server.getConnector().setProperty("address","127.0.0.1");
            var context=server.addContext("/cms",Path.of("src/main/webapp").toAbsolutePath().toString());
            context.setParentClassLoader(getClass().getClassLoader());
            context.addParameter("cms.config","classpath:cms-test.properties");
            context.addParameter("cms.db.url","jdbc:h2:mem:wire_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            context.addServletContainerInitializer((classes,sc)->new CmsWebInitializer() {
                @Override protected Class<?>[] getRootConfigClasses() {return new Class<?>[]{RootConfiguration.class,ContentTestConfiguration.class};}
            }.onStartup(sc),Set.of());
            server.start();
            db=WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext()).getBean(JdbcTemplate.class);
            base="http://127.0.0.1:"+server.getConnector().getLocalPort()+"/cms";
            db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('content-wire',?,'内容联调')",new PasswordHasher().encode(PASSWORD));
            long user=db.queryForObject("SELECT id FROM sys_user WHERE username='content-wire'",Long.class);
            db.update("INSERT INTO sys_role(code,name) VALUES ('ADMIN','Test administrator')");
            long role=db.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN'",Long.class);
            db.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",user,role,user);
            for(String permission:List.of("cms:category:read","cms:category:manage","cms:article:read","cms:article:create","cms:article:edit","cms:article:delete","cms:article:submit","cms:article:audit","cms:article:publish","cms:article:offline","cms:log:read")) {
                db.update("INSERT INTO sys_permission(code,name) VALUES (?,?)",permission,permission);
                long id=db.queryForObject("SELECT id FROM sys_permission WHERE code=?",Long.class,permission);
                db.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,id,user);
            }
        }
        @Override public void close() throws Exception {try{server.stop();}finally{server.destroy();}}
    }
    @Test void cookieLoginThroughPublicationOfflineAndAuditUseRealHttp() throws Exception {
        try(var app=new Harness()) {
            var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            var anonymous=HttpClient.newHttpClient();
            send(client,app.base,"GET","/api/articles",null,null,401);
            String token=send(client,app.base,"GET","/api/auth/csrf-token",null,null,200).at("/data/token").asText();
            send(client,app.base,"POST","/api/auth/login",token,Map.of("username","content-wire","password",PASSWORD),200);
            token=send(client,app.base,"GET","/api/auth/csrf-token",null,null,200).at("/data/token").asText();
            var current=send(client,app.base,"GET","/api/auth/me",null,null,200);
            assertThat(current.at("/data/permissionCodes").size()).isEqualTo(11);
            var page=client.send(HttpRequest.newBuilder(URI.create(app.base+"/content/index.html")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);assertThat(page.body()).contains("内容管理");
            long category=send(client,app.base,"POST","/api/categories",token,Map.of("name","真实HTTP分类"),200).at("/data/id").asLong();
            long id=send(client,app.base,"POST","/api/articles",token,Map.of("title","真实HTTP文章","content","<p>正文</p>","categoryId",category),200).at("/data/id").asLong();
            send(client,app.base,"POST","/api/articles/"+id+"/submit",token,Map.of("version",0),200);
            send(client,app.base,"POST","/api/articles/"+id+"/approve",token,Map.of("version",1),200);
            send(client,app.base,"POST","/api/articles/"+id+"/publish",token,Map.of("version",2),200);
            send(anonymous,app.base,"GET","/api/public/articles/"+id,null,null,200);
            send(client,app.base,"POST","/api/articles/"+id+"/offline",token,Map.of("version",3),200);
            send(anonymous,app.base,"GET","/api/public/articles/"+id,null,null,404);
            assertThat(app.db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE module='ARTICLE' AND result='SUCCESS'",Long.class)).isEqualTo(5);
            assertThat(app.db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history",Long.class)).isEqualTo(5);
        }
    }
    static JsonNode send(HttpClient client,String base,String method,String path,String token,Object body,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path));if(token!=null)request.header("X-CSRF-Token",token);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method+" "+path+" "+response.body()).isEqualTo(status);
        var result=JSON.readTree(response.body());assertThat(result.path("requestId").asText()).isNotBlank();return result;
    }
    /** Explicit test-only preview entry for browser integration against real controllers. */
    public static void main(String[] arguments) throws Exception {
        var app=new Harness();Runtime.getRuntime().addShutdownHook(new Thread(()->{try{app.close();}catch(Exception ignored){}}));
        System.out.println("A_CONTENT_PREVIEW_BASE="+app.base);new CountDownLatch(1).await();
    }
}
