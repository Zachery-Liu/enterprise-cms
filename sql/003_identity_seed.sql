-- enterprise-cms B module roles, permissions, grants, and demo users
-- Target: MySQL 8.0.16+
-- Prerequisite: sql/001_identity_schema.sql
-- Password values below are PBKDF2-HMAC-SHA256 digests in the B-01 format.
-- No plaintext password is stored in this file. Rotate demo credentials before deployment.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

START TRANSACTION;

INSERT INTO sys_user (
    id, username, password_hash, display_name, status,
    password_changed_at, version, deleted, created_by, updated_by
) VALUES
    (
        1, 'admin',
        '{pbkdf2-sha256}600000$9o1X6EkRQpvY6UzPUcOFFQ==$MytbS7OKZd2mI8VvAltrY4AlShwsphC0XW3Y3DOLPpY=',
        '系统管理员', 'ACTIVE', CURRENT_TIMESTAMP(3), 0, 0, NULL, NULL
    ),
    (
        2, 'editor',
        '{pbkdf2-sha256}600000$QNeV6F/LV5CxWc9GurImLw==$cU5pfuHe0Hnc0Ui4PeUC2W48Rh3hi9UYkYnvA6wb5Jk=',
        '内容编辑', 'ACTIVE', CURRENT_TIMESTAMP(3), 0, 0, NULL, NULL
    ),
    (
        3, 'reviewer',
        '{pbkdf2-sha256}600000$J/ML9XsiykYo9xqd9CPb1Q==$29qRAR9wAYCW8AhUfM68VKX16kLh2VWoctzS8Nl+a2o=',
        '内容审核', 'ACTIVE', CURRENT_TIMESTAMP(3), 0, 0, NULL, NULL
    ),
    (
        4, 'publisher',
        '{pbkdf2-sha256}600000$J/P+GB6Jx8zjuf7A14wlJA==$MREznPUvw0pmJRpbdT0RoHPTHtrYAV8HqKGBtlu2Pbs=',
        '内容发布', 'ACTIVE', CURRENT_TIMESTAMP(3), 0, 0, NULL, NULL
    );

INSERT INTO sys_role (
    id, code, name, description, status, built_in,
    version, deleted, created_by, updated_by
) VALUES
    (1, 'ADMIN', '系统管理员', '拥有全部系统权限', 'ENABLED', 1, 0, 0, NULL, NULL),
    (2, 'EDITOR', '内容编辑', '维护分类并编辑和提交文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (3, 'REVIEWER', '内容审核', '审核文章并给出审核意见', 'ENABLED', 1, 0, 0, NULL, NULL),
    (4, 'PUBLISHER', '内容发布', '发布和下架已审核文章', 'ENABLED', 1, 0, 0, NULL, NULL);

INSERT INTO sys_permission (
    id, code, name, description, status, built_in,
    version, deleted, created_by, updated_by
) VALUES
    (1, 'cms:user:read', '查询用户', '查询用户列表和详情', 'ENABLED', 1, 0, 0, NULL, NULL),
    (2, 'cms:user:manage', '管理用户', '启用、停用和管理用户', 'ENABLED', 1, 0, 0, NULL, NULL),
    (3, 'cms:role:assign', '分配角色', '配置用户的角色集合', 'ENABLED', 1, 0, 0, NULL, NULL),
    (4, 'cms:permission:manage', '配置权限', '配置角色的权限集合', 'ENABLED', 1, 0, 0, NULL, NULL),
    (5, 'cms:category:read', '查询分类', '查询内容分类', 'ENABLED', 1, 0, 0, NULL, NULL),
    (6, 'cms:category:manage', '管理分类', '新增、修改、移动、启停和删除分类', 'ENABLED', 1, 0, 0, NULL, NULL),
    (7, 'cms:article:read', '查询文章', '查询后台文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (8, 'cms:article:create', '新增文章', '创建文章草稿', 'ENABLED', 1, 0, 0, NULL, NULL),
    (9, 'cms:article:edit', '编辑文章', '编辑允许修改的文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (10, 'cms:article:delete', '删除文章', '逻辑删除允许删除的文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (11, 'cms:article:submit', '提交审核', '将文章提交审核', 'ENABLED', 1, 0, 0, NULL, NULL),
    (12, 'cms:article:audit', '审核文章', '审核通过或退回文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (13, 'cms:article:publish', '发布文章', '发布已审核文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (14, 'cms:article:offline', '下架文章', '下架已发布文章', 'ENABLED', 1, 0, 0, NULL, NULL),
    (15, 'cms:log:read', '查询日志', '分页查询操作日志', 'ENABLED', 1, 0, 0, NULL, NULL);

INSERT INTO sys_user_role (user_id, role_id, created_by) VALUES
    (1, 1, 1),
    (2, 2, 1),
    (3, 3, 1),
    (4, 4, 1);

INSERT INTO sys_role_permission (role_id, permission_id, created_by) VALUES
    (1, 1, 1),
    (1, 2, 1),
    (1, 3, 1),
    (1, 4, 1),
    (1, 5, 1),
    (1, 6, 1),
    (1, 7, 1),
    (1, 8, 1),
    (1, 9, 1),
    (1, 10, 1),
    (1, 11, 1),
    (1, 12, 1),
    (1, 13, 1),
    (1, 14, 1),
    (1, 15, 1),
    (2, 5, 1),
    (2, 7, 1),
    (2, 8, 1),
    (2, 9, 1),
    (2, 10, 1),
    (2, 11, 1),
    (3, 5, 1),
    (3, 7, 1),
    (3, 12, 1),
    (4, 5, 1),
    (4, 7, 1),
    (4, 13, 1),
    (4, 14, 1);

COMMIT;
