CREATE TABLE IF NOT EXISTS sys_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(40) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'ENABLED',
 deleted INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT ck_user_status CHECK (status IN ('ENABLED','DISABLED')),
 CONSTRAINT ck_user_deleted CHECK (deleted IN (0,1))
);
CREATE TABLE IF NOT EXISTS sys_role (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(40) NOT NULL UNIQUE,
 name VARCHAR(80) NOT NULL
);
CREATE TABLE IF NOT EXISTS sys_permission (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(80) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS sys_user_role (
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 PRIMARY KEY (user_id,role_id),
 CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);
CREATE TABLE IF NOT EXISTS sys_role_permission (
 role_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 PRIMARY KEY (role_id,permission_id),
 CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
 CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);
CREATE TABLE IF NOT EXISTS sys_operation_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 operator_id BIGINT,
 operator_name VARCHAR(40),
 module VARCHAR(40) NOT NULL,
 action VARCHAR(60) NOT NULL,
 target_id VARCHAR(255),
 result VARCHAR(12) NOT NULL,
 error_code VARCHAR(60),
 request_id VARCHAR(40) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_log_user FOREIGN KEY (operator_id) REFERENCES sys_user(id),
 CONSTRAINT ck_log_result CHECK (result IN ('SUCCESS','FAILURE','DENIED'))
);
