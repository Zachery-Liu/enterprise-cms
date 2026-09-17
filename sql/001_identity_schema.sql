-- enterprise-cms B module identity, RBAC, and operation-log schema
-- Target: MySQL 8.0.16+
-- Must run before: sql/002_content_schema.sql

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'User primary key',
    username VARCHAR(64) NOT NULL COMMENT 'Unique login name',
    password_hash VARCHAR(255) NOT NULL COMMENT 'Encoded password digest; never plaintext',
    display_name VARCHAR(100) NOT NULL COMMENT 'Display name',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or DISABLED',
    password_changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT 'Latest password change time',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Logical delete: 0 active, 1 deleted',
    created_by BIGINT UNSIGNED NULL COMMENT 'Creator user id; NULL for registration or bootstrap',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_by BIGINT UNSIGNED NULL COMMENT 'Last updater user id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    CONSTRAINT pk_sys_user PRIMARY KEY (id),
    CONSTRAINT uq_sys_user_username UNIQUE (username),
    CONSTRAINT fk_user_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_user_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_user_username_not_blank CHECK (
        CHAR_LENGTH(TRIM(username)) BETWEEN 3 AND 64
    ),
    CONSTRAINT chk_user_password_hash_not_blank CHECK (
        CHAR_LENGTH(TRIM(password_hash)) > 0
    ),
    CONSTRAINT chk_user_display_name_not_blank CHECK (
        CHAR_LENGTH(TRIM(display_name)) BETWEEN 1 AND 100
    ),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_user_deleted CHECK (deleted IN (0, 1)),
    INDEX idx_user_admin_page (deleted, created_at, id),
    INDEX idx_user_status_page (status, deleted, created_at, id),
    INDEX idx_user_created_by (created_by),
    INDEX idx_user_updated_by (updated_by)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'System user account';

CREATE TABLE sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Role primary key',
    code VARCHAR(64) NOT NULL COMMENT 'Stable unique role code',
    name VARCHAR(100) NOT NULL COMMENT 'Role display name',
    description VARCHAR(500) NULL COMMENT 'Role description',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED or DISABLED',
    built_in TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Built-in role: 0 no, 1 yes',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Logical delete: 0 active, 1 deleted',
    created_by BIGINT UNSIGNED NULL COMMENT 'Creator user id; NULL for bootstrap',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_by BIGINT UNSIGNED NULL COMMENT 'Last updater user id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    CONSTRAINT pk_sys_role PRIMARY KEY (id),
    CONSTRAINT uq_sys_role_code UNIQUE (code),
    CONSTRAINT fk_role_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_role_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_role_code_not_blank CHECK (CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT chk_role_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_role_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_role_built_in CHECK (built_in IN (0, 1)),
    CONSTRAINT chk_role_deleted CHECK (deleted IN (0, 1)),
    INDEX idx_role_status (status, deleted, id),
    INDEX idx_role_created_by (created_by),
    INDEX idx_role_updated_by (updated_by)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'RBAC role';

CREATE TABLE sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Permission primary key',
    code VARCHAR(100) NOT NULL COMMENT 'Stable unique permission code',
    name VARCHAR(100) NOT NULL COMMENT 'Permission display name',
    description VARCHAR(500) NULL COMMENT 'Permission description',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED or DISABLED',
    built_in TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Built-in permission: 0 no, 1 yes',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Logical delete: 0 active, 1 deleted',
    created_by BIGINT UNSIGNED NULL COMMENT 'Creator user id; NULL for bootstrap',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_by BIGINT UNSIGNED NULL COMMENT 'Last updater user id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    CONSTRAINT pk_sys_permission PRIMARY KEY (id),
    CONSTRAINT uq_sys_permission_code UNIQUE (code),
    CONSTRAINT fk_permission_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_permission_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_permission_code_not_blank CHECK (CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT chk_permission_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_permission_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_permission_built_in CHECK (built_in IN (0, 1)),
    CONSTRAINT chk_permission_deleted CHECK (deleted IN (0, 1)),
    INDEX idx_permission_status (status, deleted, id),
    INDEX idx_permission_created_by (created_by),
    INDEX idx_permission_updated_by (updated_by)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'RBAC permission';

CREATE TABLE sys_user_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'User-role relation primary key',
    user_id BIGINT UNSIGNED NOT NULL COMMENT 'User id',
    role_id BIGINT UNSIGNED NOT NULL COMMENT 'Role id',
    created_by BIGINT UNSIGNED NOT NULL COMMENT 'Assignment operator user id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Assignment time',
    CONSTRAINT pk_sys_user_role PRIMARY KEY (id),
    CONSTRAINT uq_user_role_pair UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id)
        REFERENCES sys_role (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_user_role_role (role_id, user_id),
    INDEX idx_user_role_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Current user-role assignments';

CREATE TABLE sys_role_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Role-permission relation primary key',
    role_id BIGINT UNSIGNED NOT NULL COMMENT 'Role id',
    permission_id BIGINT UNSIGNED NOT NULL COMMENT 'Permission id',
    created_by BIGINT UNSIGNED NOT NULL COMMENT 'Grant operator user id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Grant time',
    CONSTRAINT pk_sys_role_permission PRIMARY KEY (id),
    CONSTRAINT uq_role_permission_pair UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id)
        REFERENCES sys_role (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id)
        REFERENCES sys_permission (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_role_permission_permission (permission_id, role_id),
    INDEX idx_role_permission_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Current role-permission grants';

CREATE TABLE sys_operation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Operation log primary key',
    operator_id BIGINT UNSIGNED NULL COMMENT 'Operator user id; NULL for anonymous events',
    operator_name VARCHAR(100) NULL COMMENT 'Operator display-name snapshot',
    module VARCHAR(64) NOT NULL COMMENT 'Stable module code',
    action VARCHAR(64) NOT NULL COMMENT 'Stable action code',
    target_type VARCHAR(64) NULL COMMENT 'Target object type',
    target_id BIGINT UNSIGNED NULL COMMENT 'Target numeric id; no polymorphic foreign key',
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS or FAILURE',
    summary VARCHAR(500) NULL COMMENT 'Sanitized non-sensitive summary',
    error_code VARCHAR(64) NULL COMMENT 'Stable business error code for failures',
    request_id VARCHAR(64) NOT NULL COMMENT 'X-Request-Id value',
    ip_address VARCHAR(45) NULL COMMENT 'Normalized IPv4 or IPv6 address',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Event time',
    CONSTRAINT pk_sys_operation_log PRIMARY KEY (id),
    CONSTRAINT fk_operation_log_operator FOREIGN KEY (operator_id)
        REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_log_module_not_blank CHECK (CHAR_LENGTH(TRIM(module)) > 0),
    CONSTRAINT chk_log_action_not_blank CHECK (CHAR_LENGTH(TRIM(action)) > 0),
    CONSTRAINT chk_log_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT chk_log_request_id_not_blank CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
    INDEX idx_log_time (created_at, id),
    INDEX idx_log_operator_time (operator_id, created_at, id),
    INDEX idx_log_module_action_time (module, action, created_at, id),
    INDEX idx_log_result_time (result, created_at, id),
    INDEX idx_log_request (request_id, id),
    INDEX idx_log_target (target_type, target_id, created_at, id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Append-only business operation log';
