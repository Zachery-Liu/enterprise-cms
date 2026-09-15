CREATE INDEX idx_user_status_created ON sys_user(status, deleted, created_at, id);
CREATE INDEX idx_ur_role ON sys_user_role(role_id);
CREATE INDEX idx_rp_permission ON sys_role_permission(permission_id);
CREATE INDEX idx_log_time ON sys_operation_log(occurred_at, id);
CREATE INDEX idx_log_operator_time ON sys_operation_log(operator_id, occurred_at);
CREATE INDEX idx_log_action_time ON sys_operation_log(action, occurred_at);
