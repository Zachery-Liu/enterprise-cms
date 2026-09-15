package com.zachery.cms.modules.content.publication;

/** Command metadata for the lifecycle service; this does not perform authorization. */
public enum ArticleAction {
    SUBMIT("cms:article:submit"), APPROVE("cms:article:audit"), REJECT("cms:article:audit"),
    PUBLISH("cms:article:publish"), OFFLINE("cms:article:offline"), REOPEN("cms:article:edit");

    private final String permissionCode;
    ArticleAction(String permissionCode) { this.permissionCode = permissionCode; }
    public String permissionCode() { return permissionCode; }
}
