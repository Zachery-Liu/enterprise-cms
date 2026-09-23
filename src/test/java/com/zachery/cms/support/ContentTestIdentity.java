package com.zachery.cms.support;

import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

/** Test-only service entry: use B's real authentication interceptor to establish the request principal. */
public class ContentTestIdentity {
    public void protectedEndpoint() { }
    public static void authenticate(SessionAuthenticationInterceptor authentication) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        try {
            authentication.preHandle(attributes.getRequest(), new MockHttpServletResponse(),
                    new HandlerMethod(new ContentTestIdentity(), ContentTestIdentity.class.getMethod("protectedEndpoint")));
        } catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
    }
}
