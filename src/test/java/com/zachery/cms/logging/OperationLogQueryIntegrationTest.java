package com.zachery.cms.logging;

import com.fasterxml.jackson.databind.*;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.SpringWebIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties="cms.db.url=jdbc:h2:mem:logquery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OperationLogQueryIntegrationTest extends SpringWebIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    long admin, plain, role, permission, first, second, third;
    static final String PATH = "/api/operation-logs";

    @BeforeEach void seed() {
        for (String table : List.of("sys_user_role","sys_role_permission","sys_operation_log","sys_user","sys_role","sys_permission"))
            jdbc.update("DELETE FROM " + table);
        admin = user("query_admin");
        plain = user("query_plain");
        jdbc.update("INSERT INTO sys_role(code,name,status) VALUES ('ADMIN','Admin','ENABLED')");
        role = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN'", Long.class);
        jdbc.update("INSERT INTO sys_permission(code,name,status) VALUES ('cms:log:read','Logs','ENABLED')");
        permission = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='cms:log:read'", Long.class);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)", admin,role,admin);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,permission,admin);
        first = log(plain,"historical_name","ARTICLE","PUBLISH","SUCCESS","2026-10-11 01:00:00.123","first");
        second = log(plain,"historical_name","ARTICLE","PUBLISH","FAILURE","2026-10-11 01:00:00.123","second");
        third = log(null,null,"AUTH","LOGIN","FAILURE","2026-10-11 02:00:00.000","third");
    }

    @Test void defaultsReturnSafeUtcSnapshotsAndDoNotWriteAuditRows() throws Exception {
        var result = mvc.perform(get(PATH).session(session(admin)).header("X-Request-Id","log-query-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.requestId").value("log-query-test")).andReturn();
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("page").asLong()).isEqualTo(1);
        assertThat(data.path("size").asLong()).isEqualTo(20);
        assertThat(data.path("total").asLong()).isEqualTo(3);
        assertThat(data.path("records").get(0).path("id").asLong()).isEqualTo(third);
        assertThat(data.path("records").get(0).path("operatorId").isNull()).isTrue();
        assertThat(data.path("records").get(1).path("createdAt").asText()).isEqualTo("2026-10-11T01:00:00.123Z");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password","hash-fixture","CMS_CURRENT_USER_ID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class)).isEqualTo(3);
    }

    @Test void pagesUseTimeThenIdDescendingWithoutDuplicateRows() throws Exception {
        long[] ids = {third,second,first};
        for (int i=0;i<3;i++) {
            JsonNode data = query(Map.of("page",""+(i+1),"size","1"));
            assertThat(data.path("records").size()).isEqualTo(1);
            assertThat(data.path("records").get(0).path("id").asLong()).isEqualTo(ids[i]);
            assertThat(data.path("total").asLong()).isEqualTo(3);
        }
    }

    @Test void combinesAllFiltersAndConvertsOffsetBoundsInclusively() throws Exception {
        JsonNode data = query(Map.of("operatorId",""+plain,"operatorName","historical_name",
                "module","ARTICLE","action","PUBLISH","result","SUCCESS",
                "from","2026-10-11T09:00:00.123+08:00","to","2026-10-11T01:00:00.123Z"));
        assertThat(data.path("total").asLong()).isEqualTo(1);
        assertThat(data.path("records").get(0).path("id").asLong()).isEqualTo(first);
    }

    @Test void eachFilterWorksAloneAndSingleSidedRangesWork() throws Exception {
        for (String key : List.of("operatorId","operatorName","module","action","result")) {
            String value = switch(key) {
                case "operatorId" -> ""+plain; case "operatorName" -> "historical_name";
                case "module" -> "ARTICLE"; case "action" -> "PUBLISH"; default -> "FAILURE";
            };
            assertThat(query(Map.of(key,value)).path("total").asLong()).as(key).isEqualTo(2);
        }
        assertThat(query(Map.of("from","2026-10-11T01:00:00.124Z")).path("total").asLong()).isEqualTo(1);
        assertThat(query(Map.of("to","2026-10-11T01:00:00.122Z")).path("total").asLong()).isZero();
    }

    @Test void emptyAndBeyondLastPagePreserveCorrectTotals() throws Exception {
        JsonNode empty = query(Map.of("module","UNKNOWN"));
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(empty.path("records").isArray()).isTrue();
        assertThat(empty.path("records").size()).isZero();
        JsonNode beyond = query(Map.of("page","4","size","1"));
        assertThat(beyond.path("total").asLong()).isEqualTo(3);
        assertThat(beyond.path("records").size()).isZero();
        assertThat(query(Map.of("page","9223372036854775807","size","1")).path("records").size()).isZero();
    }

    @Test void invalidParametersReturnSafeFieldErrors() throws Exception {
        List<Map<String,String>> invalid = List.of(
                Map.of("page","0"),Map.of("page","abc"),Map.of("page","9223372036854775808"),
                Map.of("page","9223372036854775807","size","100"),Map.of("size","101"),Map.of("size","0"),
                Map.of("operatorId","-1"),Map.of("operatorId","no"),Map.of("operatorName"," "),
                Map.of("operatorName","x".repeat(101)),Map.of("module","article"),Map.of("action","X".repeat(65)),
                Map.of("module","A' OR 1=1"),Map.of("result","ALL"),Map.of("from","2026-10-11T01:00:00"),
                Map.of("from","invalid"),Map.of("from","2026-10-11T01:00:00.1234Z"),
                Map.of("from","0999-12-31T23:59:59Z"),Map.of("to","+10000-01-01T00:00:00Z"),
                Map.of("from","2026-10-12T00:00:00Z","to","2026-10-11T00:00:00Z"),
                Map.of("sort","id:asc"),Map.of("moduel","AUTH"));
        for (var parameters : invalid) {
            var request=get(PATH).session(session(admin));
            parameters.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.errors").isArray());
        }
        mvc.perform(get(PATH).session(session(admin)).param("module","AUTH","ARTICLE")).andExpect(status().isBadRequest());
    }

    @Test void unauthenticatedAndUnprivilegedUsersCannotReadLogs() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).session(session(plain))).andExpect(status().isForbidden());
    }

    @Test void permissionRevocationTakesEffectOnSameSessionWithoutAdminBypass() throws Exception {
        var session=session(admin);
        mvc.perform(get(PATH).session(session)).andExpect(status().isOk());
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=?",role);
        mvc.perform(get(PATH).session(session)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",role,permission,admin);
        mvc.perform(get(PATH).session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE id=?",permission);
        mvc.perform(get(PATH).session(session)).andExpect(status().isForbidden());
    }

    @Test void disabledRoleAndDisabledAccountLoseAccess() throws Exception {
        var session=session(admin);
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE id=?",role);
        mvc.perform(get(PATH).session(session)).andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",admin);
        mvc.perform(get(PATH).session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void historicalSnapshotSurvivesUserRenameAndLogicalDeletion() throws Exception {
        jdbc.update("UPDATE sys_user SET username='renamed',display_name='Renamed',deleted=1 WHERE id=?",plain);
        JsonNode data=query(Map.of("operatorId",""+plain,"operatorName","historical_name"));
        assertThat(data.path("total").asLong()).isEqualTo(2);
        assertThat(data.path("records").get(0).path("operatorName").asText()).isEqualTo("historical_name");
    }

    @Test void operatorNameIsBoundAsLiteralNotSqlOrWildcard() throws Exception {
        for (String name : List.of("%","_","historical%","' OR 1=1 --"))
            assertThat(query(Map.of("operatorName",name)).path("total").asLong()).isZero();
    }

    @Test void modificationMethodsAreNotExposed() throws Exception {
        for (var request : List.of(post(PATH),put(PATH),patch(PATH),delete(PATH)))
            mvc.perform(request.session(session(admin))).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class)).isEqualTo(3);
    }

    private JsonNode query(Map<String,String> parameters) throws Exception {
        var request=get(PATH).session(session(admin));
        parameters.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private MockHttpSession session(long id) {
        var session=new MockHttpSession(null,"b16-session-"+UUID.randomUUID());
        session.setAttribute(SessionKeys.CURRENT_USER_ID,id);
        return session;
    }
    private long user(String name) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status) VALUES (?,'hash-fixture',?,'ACTIVE')",name,name);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    private long log(Long operator,String name,String module,String action,String result,String time,String requestId) {
        jdbc.update("INSERT INTO sys_operation_log(operator_id,operator_name,module,action,target_type,target_id,result,summary,error_code,request_id,ip_address,created_at) VALUES (?,?,?,?,'ARTICLE',10,?,?,?,?,'127.0.0.1',?)",
                operator,name,module,action,result,module+" / "+action+" / "+result,
                result.equals("FAILURE")?"COMMON_CONFLICT":null,requestId,Timestamp.valueOf(time));
        return jdbc.queryForObject("SELECT id FROM sys_operation_log WHERE request_id=?",Long.class,requestId);
    }
}
