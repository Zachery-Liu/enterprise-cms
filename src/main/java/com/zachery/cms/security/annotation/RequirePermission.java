package com.zachery.cms.security.annotation;

import java.lang.annotation.*;

/** The caller must hold the primary permission or one explicitly listed alternative. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    String value();
    String[] alternatives() default {};
}
