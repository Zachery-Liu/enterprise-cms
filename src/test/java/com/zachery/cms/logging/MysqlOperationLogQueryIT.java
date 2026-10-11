package com.zachery.cms.logging;

import org.springframework.test.context.TestPropertySource;

/** Opt-in: use a new empty disposable MySQL schema, never a business database. */
@TestPropertySource(properties={
    "cms.db.url=${CMS_MYSQL_TEST_URL}", "cms.db.username=${CMS_MYSQL_TEST_USER}",
    "cms.db.password=${CMS_MYSQL_TEST_PASSWORD}", "cms.db.driver=com.mysql.cj.jdbc.Driver",
    "cms.test.schema=file:sql/001_identity_schema.sql"
})
class MysqlOperationLogQueryIT extends OperationLogQueryIntegrationTest {}
