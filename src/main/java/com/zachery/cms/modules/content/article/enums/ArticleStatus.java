package com.zachery.cms.modules.content.article.enums;

/** Stable database and API values. State writes belong to ArticleLifecycleService. */
public enum ArticleStatus {
    DRAFT, PENDING, REJECTED, APPROVED, PUBLISHED, OFFLINE;

    public boolean isEditable() { return this == DRAFT || this == REJECTED; }
    public boolean isDeletable() { return isEditable() || this == OFFLINE; }
    public boolean isPubliclyVisible() { return this == PUBLISHED; }
}
