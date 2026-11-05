package com.zachery.cms.support;

import com.zachery.cms.common.context.RequestIdFilter;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;

@Configuration(proxyBeanMethods = false)
public class MockMvcConfiguration {
    @Bean public MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true), new RequestIdFilter()).build();
    }
}
