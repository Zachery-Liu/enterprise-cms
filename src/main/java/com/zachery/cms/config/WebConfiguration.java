package com.zachery.cms.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zachery.cms.security.csrf.CsrfConfiguration;
import com.zachery.cms.security.session.SessionAuthenticationConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.validation.Validator;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
@EnableAspectJAutoProxy(proxyTargetClass = true)
@ComponentScan(basePackages = "com.zachery.cms", useDefaultFilters = false,
        includeFilters = {@ComponentScan.Filter(Controller.class), @ComponentScan.Filter(ControllerAdvice.class)},
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test\\$.*"))
@Import({CsrfConfiguration.class, SessionAuthenticationConfiguration.class})
public class WebConfiguration implements WebMvcConfigurer {
    private final Validator validator;
    public WebConfiguration(Validator validator) { this.validator = validator; }
    @Bean public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    @Override public Validator getValidator() { return validator; }
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters)
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) jackson.setObjectMapper(objectMapper());
    }
}
