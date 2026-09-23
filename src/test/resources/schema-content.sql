-- H2 test fixture only. Target MySQL DDL remains sql/002_content_schema.sql.
CREATE TABLE cms_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT REFERENCES cms_category(id),
    name VARCHAR_IGNORECASE(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED','DISABLED')),
    version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    active_parent_key BIGINT GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN COALESCE(parent_id, 0) ELSE NULL END),
    active_name VARCHAR_IGNORECASE(64) GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN name ELSE NULL END),
    created_by BIGINT NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL REFERENCES sys_user(id),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_category_active_sibling UNIQUE (active_parent_key,active_name),
    CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);
CREATE INDEX idx_category_parent_tree ON cms_category(parent_id,deleted,status,sort_order,id);
CREATE TABLE cms_article (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL REFERENCES cms_category(id),
    author_id BIGINT NOT NULL REFERENCES sys_user(id),
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500),
    content CLOB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    review_comment VARCHAR(500),
    reviewer_id BIGINT REFERENCES sys_user(id),
    reviewed_at TIMESTAMP(3),
    published_at TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL REFERENCES sys_user(id),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE cms_article_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES cms_article(id),
    operator_id BIGINT NOT NULL REFERENCES sys_user(id),
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
