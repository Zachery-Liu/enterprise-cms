package com.zachery.cms.security.session;

public final class SessionKeys {
    public static final String CURRENT_USER_ID = "CMS_CURRENT_USER_ID";
    public static final String REQUEST_USER = SessionKeys.class.getName() + ".user";
    public static final String CURRENT_USER_SNAPSHOT = SessionKeys.class.getName() + ".snapshot";
    private SessionKeys() {}
}
