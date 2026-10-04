package com.zachery.cms.security.csrf;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class CsrfConfiguration implements WebMvcConfigurer {
    private final CsrfInterceptor interceptor;

    public CsrfConfiguration(CsrfInterceptor interceptor) { this.interceptor = interceptor; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
