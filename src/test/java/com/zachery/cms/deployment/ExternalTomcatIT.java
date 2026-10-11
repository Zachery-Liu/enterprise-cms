package com.zachery.cms.deployment;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in acceptance against a real deployed WAR and disposable MySQL database; not a mock server. */
class ExternalTomcatIT {
    private final ObjectMapper json = new ObjectMapper();
    private final String base = required("CMS_HTTP_TEST_BASE");
    private final String database = required("CMS_HTTP_TEST_DB_URL");
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build(); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " for the opt-in test");
        return value;
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, Object body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("X-Request-Id", "ssm-external-http");
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("ssm-external-http");
        JsonNode tree = json.readTree(response.body());
        assertThat(tree.path("requestId").asText()).isEqualTo("ssm-external-http");
        return tree.path("data");
    }
    private String token(HttpClient client) throws Exception { return data(request(client,"GET","/api/auth/csrf-token",null,null),200).path("token").asText(); }
    private void staleCookieRejected(String cookie) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/auth/me"))
                .header("Cookie",cookie.split(";",2)[0]).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test void deployedWarPreservesAuthenticationCookiesRbacAndDatabaseState() throws Exception {
        assertThat(URI.create(base).getHost()).isIn("127.0.0.1", "localhost");
        assertThat(database).contains("cms_ssm_http"); // Explicit fixture schema, never a normal CMS database.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0,12);
        String username = "http_"+suffix, password = "FixturePass123!";
        HttpClient user = client(), manager = client();
        data(request(client(),"GET","/api/operation-logs",null,null),401);
        var csrfResponse = request(user,"GET","/api/auth/csrf-token",null,null);
        String csrf = data(csrfResponse,200).path("token").asText();
        String anonymousCookie = csrfResponse.headers().firstValue("set-cookie").orElseThrow();
        assertThat(anonymousCookie.toLowerCase(Locale.ROOT)).contains("httponly", "samesite=lax", "path="+URI.create(base).getPath());
        data(request(user,"POST","/api/auth/register",Map.of("username",username,"password",password),null),403);
        data(request(user,"POST","/api/auth/register",Map.of("username",username,"password","short"),csrf),400);
        long userId = data(request(user,"POST","/api/auth/register",Map.of("username",username,"password",password),csrf),201).path("id").asLong();
        data(request(user,"GET","/api/auth/me",null,null),401);
        // An unauthenticated protected request invalidates the anonymous session by contract.
        csrfResponse = request(user,"GET","/api/auth/csrf-token",null,null);
        csrf = data(csrfResponse,200).path("token").asText();
        anonymousCookie = csrfResponse.headers().firstValue("set-cookie").orElseThrow();
        var login = request(user,"POST","/api/auth/login",Map.of("username",username,"password",password),csrf);
        data(login,200);
        String loginCookie = login.headers().firstValue("set-cookie").orElseThrow();
        assertThat(loginCookie.split(";",2)[0]).isNotEqualTo(anonymousCookie.split(";",2)[0]);
        staleCookieRejected(anonymousCookie);
        assertThat(data(request(user,"GET","/api/auth/me",null,null),200).path("userId").asLong()).isEqualTo(userId);
        data(request(user,"GET","/api/roles",null,null),403);
        data(request(user,"GET","/api/operation-logs",null,null),403);
        data(request(user,"GET","/api/users",null,null),403);
        data(request(user,"POST","/api/auth/logout",null,csrf),403);

        String managerCsrf = token(manager);
        data(request(manager,"POST","/api/auth/login",Map.of("username",required("CMS_HTTP_TEST_ADMIN"),"password",required("CMS_HTTP_TEST_ADMIN_PASSWORD")),managerCsrf),200);
        managerCsrf = token(manager);
        long roleId;
        try (Connection connection = DriverManager.getConnection(database, required("CMS_HTTP_TEST_DB_USER"), required("CMS_HTTP_TEST_DB_PASSWORD"))) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO sys_role(code,name,status) VALUES (?,?,'ENABLED')", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1,"HTTP_"+suffix); insert.setString(2,"HTTP fixture"); insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); roleId=keys.getLong(1); }
            }
            long permissionId;
            try (PreparedStatement select=connection.prepareStatement("SELECT id FROM sys_permission WHERE code='cms:article:read'"); ResultSet rows=select.executeQuery()) {
                assertThat(rows.next()).isTrue(); permissionId=rows.getLong(1);
            }
            data(request(manager,"PUT","/api/roles/"+roleId+"/permissions",Map.of("permissionIds",List.of(permissionId)),managerCsrf),200);
            data(request(manager,"PUT","/api/users/"+userId+"/roles",Map.of("roleIds",List.of(roleId)),managerCsrf),200);
            assertThat(data(request(user,"GET","/api/auth/me",null,null),200).path("permissionCodes").toString()).isEqualTo("[\"cms:article:read\"]");
            data(request(manager,"PUT","/api/roles/"+roleId+"/permissions",Map.of("permissionIds",List.of()),managerCsrf),200);
            assertThat(data(request(user,"GET","/api/auth/me",null,null),200).path("permissionCodes").size()).isZero();
            String rotated = token(user);
            assertThat(rotated).isNotEqualTo(csrf);
            data(request(user,"POST","/api/auth/logout",null,rotated),200);
            staleCookieRejected(loginCookie);
            csrf = token(user);
            data(request(user,"POST","/api/auth/login",Map.of("username",username,"password",password),csrf),200);
            JsonNode accountPage=data(request(manager,"GET","/api/users?username="+username+"&status=ACTIVE",null,null),200);
            assertThat(accountPage.path("total").asLong()).isEqualTo(1);
            JsonNode account=accountPage.path("records").get(0);
            assertThat(account.path("id").asLong()).isEqualTo(userId);
            assertThat(account.has("passwordHash")).isFalse();
            int accountVersion=account.path("version").asInt();
            JsonNode stopped=data(request(manager,"PUT","/api/users/"+userId+"/status",
                    Map.of("status","DISABLED","version",accountVersion),managerCsrf),200);
            assertThat(stopped.path("version").asInt()).isEqualTo(accountVersion+1);
            data(request(user,"GET","/api/auth/me",null,null),401);
            data(request(manager,"PUT","/api/users/"+userId+"/status",
                    Map.of("status","ACTIVE","version",accountVersion),managerCsrf),409);
            data(request(manager,"PUT","/api/users/"+userId+"/status",
                    Map.of("status","ACTIVE","version",accountVersion+1),managerCsrf),200);
            assertAuditCount(connection, "AUTH", "REGISTER", userId, 1);
            assertAuditCount(connection, "AUTH", "LOGIN", userId, 2);
            assertAuditCount(connection, "AUTH", "LOGOUT", userId, 1);
            assertAuditCount(connection, "USER", "ASSIGN_ROLES", userId, 1);
            assertAuditCount(connection, "ROLE", "ASSIGN_PERMISSIONS", roleId, 2);
            assertAuditCount(connection, "USER", "SET_STATUS", userId, 2);
            JsonNode audit = data(request(manager,"GET","/api/operation-logs?operatorId="+userId
                    +"&module=AUTH&action=LOGIN&result=SUCCESS&size=1",null,null),200);
            assertThat(audit.path("total").asLong()).isEqualTo(2);
            assertThat(audit.path("records").size()).isEqualTo(1);
            assertThat(audit.path("records").get(0).path("operatorId").asLong()).isEqualTo(userId);
            assertThat(audit.path("records").get(0).path("createdAt").asText()).endsWith("Z");
            data(request(manager,"GET","/api/operation-logs?size=101",null,null),400);
        }
        data(request(manager,"POST","/api/auth/logout",null,managerCsrf),200);
    }

    private void assertAuditCount(Connection connection, String module, String action, long target, int expected) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM sys_operation_log WHERE module=? AND action=? AND target_id=? AND result='SUCCESS' AND request_id='ssm-external-http'")) {
            select.setString(1,module); select.setString(2,action); select.setLong(3,target);
            try (ResultSet rows = select.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(expected); }
        }
    }
}
