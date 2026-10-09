package com.zachery.cms.modules.identity.role;

import com.zachery.cms.config.*;
import com.zachery.cms.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: -Dtest=MysqlAssignmentIT; requires an EMPTY disposable MySQL schema. */
@TestPropertySource(properties = {
        "cms.db.url=${CMS_MYSQL_TEST_URL}", "cms.db.username=${CMS_MYSQL_TEST_USER}",
        "cms.db.password=${CMS_MYSQL_TEST_PASSWORD}", "cms.db.driver=com.mysql.cj.jdbc.Driver",
        "cms.test.schema=file:sql/001_identity_schema.sql"
})
@ContextHierarchy({
        @ContextConfiguration(name = "root", classes = {RootConfiguration.class, TestDatabaseConfiguration.class, RbacAssignmentIntegrationTest.ProbeConfiguration.class}),
        @ContextConfiguration(name = "web", classes = {WebConfiguration.class, MockMvcConfiguration.class})
})
class MysqlAssignmentIT extends RbacAssignmentIntegrationTest {
    @Test void actualMysqlForeignKeyRejectsDanglingRole() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)", target, Long.MAX_VALUE, admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?", Long.class, target)).containsExactly(editor);
    }
}
