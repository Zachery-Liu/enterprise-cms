package com.zachery.cms.logging.aspect;

import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import java.lang.reflect.Method;

/** Read-only SpEL ID paths, not executable expressions or payload serialization. */
public class TargetIdResolver {
    private final SpelExpressionParser parser = new SpelExpressionParser();
    public Long resolve(String expression, Method method, Object[] args, Object result, boolean succeeded) {
        if (expression == null || !expression.matches("#[A-Za-z][A-Za-z0-9_]*(\\.(data|body|id|[A-Za-z][A-Za-z0-9_]*Id))*")) return null;
        if (expression.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|cookie|session).*")) return null;
        if (!succeeded && expression.startsWith("#result")) return null;
        var context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        var parameters = method.getParameters();
        for (int i=0; i<parameters.length; i++) {
            String name = parameters[i].getName();
            Object value = args[i];
            if (name.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|cookie|session|content|body).*")) continue;
            // Scalars are eligible only when their declared parameter name identifies an ID.
            if ((value instanceof CharSequence || value instanceof Number) && !(name.equals("id") || name.endsWith("Id"))) continue;
            context.setVariable(name, value); context.setVariable("p"+i, value); context.setVariable("a"+i, value);
        }
        if (succeeded) context.setVariable("result", result);
        try {
            Object value = parser.parseExpression(expression).getValue(context);
            if (value == null || !(value instanceof Number || value instanceof String)) return null;
            String text = value.toString();
            if (!text.matches("[0-9]{1,19}")) return null;
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (RuntimeException invalidId) { return null; }
    }
}
