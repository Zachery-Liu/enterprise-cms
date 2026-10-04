package com.zachery.cms.security.session;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class SessionAuthenticationConfiguration implements WebMvcConfigurer {
    private final SessionAuthenticationInterceptor interceptor;
    public SessionAuthenticationConfiguration(SessionAuthenticationInterceptor interceptor) { this.interceptor = interceptor; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**").order(0);
    }
}
