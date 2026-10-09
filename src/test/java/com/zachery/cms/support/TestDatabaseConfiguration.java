package com.zachery.cms.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {
    @Bean public InitializingBean initializeSchema(DataSource dataSource, Environment environment) {
        return () -> new ResourceDatabasePopulator(new DefaultResourceLoader().getResource(
                environment.getProperty("cms.test.schema", "classpath:schema.sql"))).execute(dataSource);
    }
}
