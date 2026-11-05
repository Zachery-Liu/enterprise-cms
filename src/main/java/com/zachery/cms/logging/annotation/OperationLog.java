package com.zachery.cms.logging.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    String module();
    String action();
    String targetType() default "";
    String targetIdExpression() default "";
}
