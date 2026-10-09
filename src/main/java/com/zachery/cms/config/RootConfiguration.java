package com.zachery.cms.config;

import org.springframework.context.annotation.*;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.stereotype.Controller;
import org.springframework.validation.beanvalidation.*;
import org.springframework.web.bind.annotation.ControllerAdvice;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:cms.properties")
@PropertySource("${cms.config:classpath:cms.properties}")
@ComponentScan(basePackages = {"com.zachery.cms.modules", "com.zachery.cms.security"},
        excludeFilters = {
            @ComponentScan.Filter(Controller.class), @ComponentScan.Filter(ControllerAdvice.class),
            @ComponentScan.Filter(Configuration.class)
        })
@Import(PersistenceConfiguration.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class RootConfiguration {
    @Bean public static PropertySourcesPlaceholderConfigurer propertyPlaceholders() { return new PropertySourcesPlaceholderConfigurer(); }
    @Bean public LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
    @Bean public static MethodValidationPostProcessor methodValidation() {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setProxyTargetClass(true);
        return processor;
    }
}
