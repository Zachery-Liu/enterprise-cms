-- enterprise-cms A module schema
-- Target: MySQL 8.0
-- Dependency: B module must create sys_user(id BIGINT UNSIGNED) first.

SET NAMES utf8mb4;

CREATE TABLE cms_category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Category primary key',
    parent_id BIGINT UNSIGNED NULL COMMENT 'Parent category; NULL means root',
    name VARCHAR(64) NOT NULL COMMENT 'Category name',
    sort_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Sibling display order',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED or DISABLED',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Logical delete: 0 active, 1 deleted',
    created_by BIGINT UNSIGNED NOT NULL COMMENT 'Creator user id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT 'Last updater user id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    CONSTRAINT pk_cms_category PRIMARY KEY (id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id)
        REFERENCES cms_category (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_category_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_category_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_category_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT chk_category_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_category_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_category_deleted CHECK (deleted IN (0, 1)),
    INDEX idx_category_parent_tree (parent_id, deleted, status, sort_order, id),
    INDEX idx_category_sibling_name (parent_id, name, deleted),
    INDEX idx_category_status (status, deleted, id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'CMS content category';

CREATE TABLE cms_article (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Article primary key',
    category_id BIGINT UNSIGNED NOT NULL COMMENT 'Category id',
    author_id BIGINT UNSIGNED NOT NULL COMMENT 'Business author user id',
    title VARCHAR(200) NOT NULL COMMENT 'Article title',
    summary VARCHAR(500) NULL COMMENT 'Article summary',
    content MEDIUMTEXT NOT NULL COMMENT 'Sanitized article content',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'Article lifecycle status',
    review_comment VARCHAR(500) NULL COMMENT 'Latest review comment or rejection reason',
    reviewer_id BIGINT UNSIGNED NULL COMMENT 'Latest reviewer user id',
    reviewed_at DATETIME(3) NULL COMMENT 'Latest review time',
    published_at DATETIME(3) NULL COMMENT 'Latest successful publication time',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Logical delete: 0 active, 1 deleted',
    created_by BIGINT UNSIGNED NOT NULL COMMENT 'Creator user id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT 'Last updater user id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    CONSTRAINT pk_cms_article PRIMARY KEY (id),
    CONSTRAINT fk_article_category FOREIGN KEY (category_id)
        REFERENCES cms_category (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_article_author FOREIGN KEY (author_id)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_article_reviewer FOREIGN KEY (reviewer_id)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_article_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_article_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_article_title_not_blank CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_article_content_not_blank CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT chk_article_status CHECK (
        status IN ('DRAFT', 'PENDING', 'REJECTED', 'APPROVED', 'PUBLISHED', 'OFFLINE')
    ),
    CONSTRAINT chk_article_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT chk_article_rejected_comment CHECK (
        status <> 'REJECTED'
        OR (review_comment IS NOT NULL AND CHAR_LENGTH(TRIM(review_comment)) > 0)
    ),
    CONSTRAINT chk_article_published_time CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL
    ),
    INDEX idx_article_admin_page (deleted, updated_at, id),
    INDEX idx_article_status_page (status, deleted, updated_at, id),
    INDEX idx_article_category_page (category_id, deleted, updated_at, id),
    INDEX idx_article_author_page (author_id, deleted, updated_at, id),
    INDEX idx_article_public_page (status, deleted, published_at, id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'CMS article current content and lifecycle state';

CREATE TABLE cms_article_status_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Status history primary key',
    article_id BIGINT UNSIGNED NOT NULL COMMENT 'Article id',
    operator_id BIGINT UNSIGNED NOT NULL COMMENT 'State transition operator user id',
    action VARCHAR(32) NOT NULL COMMENT 'State transition action',
    from_status VARCHAR(16) NULL COMMENT 'Previous article status; NULL for creation',
    to_status VARCHAR(16) NOT NULL COMMENT 'New article status',
    reason VARCHAR(500) NULL COMMENT 'Review comment or transition reason',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Transition time',
    CONSTRAINT pk_article_status_history PRIMARY KEY (id),
    CONSTRAINT fk_history_article FOREIGN KEY (article_id)
        REFERENCES cms_article (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_history_operator FOREIGN KEY (operator_id)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_history_action CHECK (
        action IN ('CREATE', 'SUBMIT', 'APPROVE', 'REJECT', 'PUBLISH', 'OFFLINE', 'REOPEN')
    ),
    CONSTRAINT chk_history_from_status CHECK (
        from_status IS NULL
        OR from_status IN ('DRAFT', 'PENDING', 'REJECTED', 'APPROVED', 'PUBLISHED', 'OFFLINE')
    ),
    CONSTRAINT chk_history_to_status CHECK (
        to_status IN ('DRAFT', 'PENDING', 'REJECTED', 'APPROVED', 'PUBLISHED', 'OFFLINE')
    ),
    INDEX idx_article_history_timeline (article_id, created_at, id),
    INDEX idx_article_history_operator (operator_id, created_at, id),
    INDEX idx_article_history_action (action, created_at, id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'CMS article business status transition history';
