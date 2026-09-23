package com.zachery.cms.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

@Configuration
@Import(TestDatabaseConfiguration.class)
public class ContentTestConfiguration {
    @Bean @Profile("test") com.fasterxml.jackson.databind.ObjectMapper contentTestJson(org.springframework.validation.Validator validator) {
        return new com.zachery.cms.config.WebConfiguration(validator).objectMapper();
    }
    @Bean static CategorySelectCounter categorySelectCounter() { return new CategorySelectCounter(); }
    @Bean InitializingBean contentSchema(DataSource source) {
        return () -> new ResourceDatabasePopulator(new ClassPathResource("schema-content.sql")).execute(source);
    }
}
