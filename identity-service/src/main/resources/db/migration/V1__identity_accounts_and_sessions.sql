CREATE TABLE `user` (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 auth_version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_user_username(username),
 CHECK (status IN ('ACTIVE','DISABLED')), CHECK (auth_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `role` (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_role_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO `role`(code,name) VALUES ('STUDENT','学生'),('RESOURCE_ADMIN','资源管理员'),('SYSTEM_ADMIN','系统管理员');
CREATE TABLE identity_admin_guard (id TINYINT NOT NULL PRIMARY KEY, CHECK(id=1)) ENGINE=InnoDB;
INSERT INTO identity_admin_guard(id) VALUES(1);
CREATE TABLE user_role (
 user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,role_id), KEY ix_user_role_role(role_id),
 FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE RESTRICT,
 FOREIGN KEY(role_id) REFERENCES `role`(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_session (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 device_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, device_type VARCHAR(16) NOT NULL,
 refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 previous_token_until DATETIME(3), expires_at DATETIME(3) NOT NULL,
 revoked_at DATETIME(3), version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_session_refresh(refresh_token_hash), KEY ix_session_user(user_id,revoked_at), KEY ix_session_expiry(expires_at),
 FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE RESTRICT,
 CHECK (device_type IN ('WEB','IOS','ANDROID')), CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Retain every rotated hash for the session lifetime, not just the most recent one.
CREATE TABLE used_refresh_token (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id BIGINT NOT NULL, expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY ix_used_refresh_expiry(expires_at),
 FOREIGN KEY(session_id) REFERENCES user_session(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE idempotency_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NOT NULL,
 operation VARCHAR(64) NOT NULL, request_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii NOT NULL, status VARCHAR(16) NOT NULL,
 http_status INT, response_body JSON, expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_identity_idempotency(actor_id,operation,request_key), KEY ix_identity_idempotency_expiry(expires_at),
 CHECK (status IN ('IN_PROGRESS','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE audit_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT,
 action VARCHAR(64) NOT NULL, object_type VARCHAR(32) NOT NULL, object_id BIGINT NOT NULL,
 from_status VARCHAR(32), to_status VARCHAR(32), reason VARCHAR(500), trace_id VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY ix_identity_audit_object(object_type,object_id,created_at), KEY ix_identity_audit_actor(actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
