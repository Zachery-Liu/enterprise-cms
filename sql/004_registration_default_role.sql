-- B-08: ordinary registration role, no initial permissions.
-- Apply after 001_identity_schema.sql and 003_identity_seed.sql.
-- Preserve existing role state/permissions; do not silently re-enable a disabled role.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

INSERT INTO sys_role (code, name, description, status, built_in, version, deleted, created_by, updated_by)
SELECT 'USER', '普通用户', '公开注册的默认角色；初始无后台操作权限', 'ENABLED', 1, 0, 0, NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'USER');
