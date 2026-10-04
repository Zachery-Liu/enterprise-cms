package com.zachery.cms.security.annotation;

import java.lang.annotation.*;

/** Explicit anonymous endpoint; unsafe methods still require CSRF protection. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AnonymousAccess {}
