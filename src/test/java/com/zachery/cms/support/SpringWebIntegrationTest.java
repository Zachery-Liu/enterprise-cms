package com.zachery.cms.support;

import com.zachery.cms.config.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@TestPropertySource("classpath:cms-test.properties")
@ContextHierarchy({
        @ContextConfiguration(name = "root", classes = {RootConfiguration.class, TestDatabaseConfiguration.class}),
        @ContextConfiguration(name = "web", classes = {WebConfiguration.class, MockMvcConfiguration.class})
})
public abstract class SpringWebIntegrationTest {}
