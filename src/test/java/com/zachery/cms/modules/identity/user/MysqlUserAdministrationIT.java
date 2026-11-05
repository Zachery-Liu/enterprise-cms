package com.zachery.cms.modules.identity.user;

import org.springframework.test.context.TestPropertySource;

/** Requires a new EMPTY disposable MySQL schema. */
@TestPropertySource(properties={
    "cms.db.url=${CMS_MYSQL_TEST_URL}", "cms.db.username=${CMS_MYSQL_TEST_USER}",
    "cms.db.password=${CMS_MYSQL_TEST_PASSWORD}", "cms.db.driver=com.mysql.cj.jdbc.Driver",
    "cms.test.schema=file:sql/001_identity_schema.sql"
})
class MysqlUserAdministrationIT extends UserAdministrationIntegrationTest {}
