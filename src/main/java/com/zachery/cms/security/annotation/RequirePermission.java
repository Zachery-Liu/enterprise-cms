package com.zachery.cms.security.annotation;

import java.lang.annotation.*;

/** One exact permission on a public, non-final MVC controller method invoked through Spring. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    String value();
}
