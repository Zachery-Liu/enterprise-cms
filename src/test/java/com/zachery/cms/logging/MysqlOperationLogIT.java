package com.zachery.cms.logging;

import com.zachery.cms.config.*;
import com.zachery.cms.support.*;
import org.springframework.test.context.*;

/** Opt-in, requires a new EMPTY disposable schema with MySQL 8.0 constraints. */
@TestPropertySource(properties={
    "cms.db.url=${CMS_MYSQL_TEST_URL}", "cms.db.username=${CMS_MYSQL_TEST_USER}",
    "cms.db.password=${CMS_MYSQL_TEST_PASSWORD}", "cms.db.driver=com.mysql.cj.jdbc.Driver",
    "cms.test.schema=file:sql/001_identity_schema.sql"
})
@ContextHierarchy({
    @ContextConfiguration(name="root", classes={RootConfiguration.class, TestDatabaseConfiguration.class, OperationLogIntegrationTest.Fixtures.class}),
    @ContextConfiguration(name="web", classes={WebConfiguration.class, MockMvcConfiguration.class, OperationLogIntegrationTest.ProbeController.class})
})
class MysqlOperationLogIT extends OperationLogIntegrationTest {}
