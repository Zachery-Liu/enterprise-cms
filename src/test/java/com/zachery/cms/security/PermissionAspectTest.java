package com.zachery.cms.security;

import com.zachery.cms.common.context.*;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.security.annotation.RequirePermission;
import com.zachery.cms.security.aspect.PermissionAspect;
import com.zachery.cms.security.session.SessionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PermissionAspectTest {
    @Test void selfInvocationBypassesAdviceSoEveryExternalEntryNeedsItsOwnPermission() {
        CurrentUser editor = new CurrentUser(2L,"editor","Editor",UserStatus.ACTIVE,
                Set.of("EDITOR"),Set.of("cms:article:edit"));
        CurrentUserContext context = new CurrentUserContext() {
            public Optional<CurrentUser> getCurrentUser() { return Optional.of(editor); }
            public CurrentUser getRequiredUser() { return editor; }
        };
        var target = new SelfInvocationExample();
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new PermissionAspect(context));
        SelfInvocationExample proxy = factory.getProxy();
        assertThatThrownBy(proxy::publish).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("AUTH_FORBIDDEN"));
        assertThat(target.writes).isZero();
        // A deliberately unsafe TEST-ONLY example: this.publish() does not cross the proxy.
        proxy.unsafeInternalEntry();
        assertThat(target.writes).isEqualTo(1);
        assertThatThrownBy(proxy::protectedEntry).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("AUTH_FORBIDDEN"));
        assertThat(target.writes).isEqualTo(1);
    }

    /** Not a Controller; never part of the production WAR. */
    public static class SelfInvocationExample {
        int writes;
        @RequirePermission("cms:article:publish")
        public void publish() { writes++; }
        public void unsafeInternalEntry() { this.publish(); }
        @RequirePermission("cms:article:publish")
        public void protectedEntry() { this.publish(); }
    }

    @Test void inactiveSnapshotFailsClosedAndInvalidatesAnyRemainingSession() {
        CurrentUser inactive = new CurrentUser(1L,"disabled","Disabled",UserStatus.DISABLED,
                Set.of("ADMIN"),Set.of("cms:category:read"));
        CurrentUserContext context = new CurrentUserContext() {
            public Optional<CurrentUser> getCurrentUser() { return Optional.of(inactive); }
            public CurrentUser getRequiredUser() { return inactive; }
        };
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        request.setAttribute(SessionKeys.CURRENT_USER_SNAPSHOT,inactive);
        request.setAttribute(SessionKeys.REQUEST_USER,"stale");
        var factory = new AspectJProxyFactory(new ProtectedTarget());
        factory.addAspect(new PermissionAspect(context));
        ProtectedTarget proxy = factory.getProxy();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThatThrownBy(proxy::read).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo("AUTH_UNAUTHENTICATED"));
            assertThat(session.isInvalid()).isTrue();
            assertThat(request.getAttribute(SessionKeys.CURRENT_USER_SNAPSHOT)).isNull();
            assertThat(request.getAttribute(SessionKeys.REQUEST_USER)).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    public static class ProtectedTarget {
        @RequirePermission("cms:category:read")
        public void read() { throw new AssertionError("Inactive account reached the target"); }
    }
}
