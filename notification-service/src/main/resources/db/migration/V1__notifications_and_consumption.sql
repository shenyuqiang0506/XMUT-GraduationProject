-- 通知库独立归通知服务所有，用户与业务对象仅为跨服务逻辑引用。
CREATE TABLE consumed_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,consumer_name VARCHAR(64) NOT NULL,event_id CHAR(36) CHARACTER SET ascii NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,processed_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_consumed_event(consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,recipient_id BIGINT NOT NULL,event_id CHAR(36) CHARACTER SET ascii NOT NULL,
 delivery_scope VARCHAR(100) NOT NULL,kind VARCHAR(32) NOT NULL,title VARCHAR(200) NOT NULL,body TEXT NOT NULL,
 object_type VARCHAR(32) NOT NULL,object_id BIGINT NOT NULL,aggregate_version INT NOT NULL,
 occurred_at DATETIME(3) NOT NULL,read_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_notification_recipient_event(recipient_id,event_id,kind),
 KEY ix_notification_inbox(delivery_scope,recipient_id,read_at,created_at),
 CHECK(object_type IN ('APPLICATION','WAITLIST')),CHECK(aggregate_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE delivery_attempt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,notification_id BIGINT NOT NULL,channel VARCHAR(16) NOT NULL,status VARCHAR(16) NOT NULL,
 attempt_no INT NOT NULL,provider_reference VARCHAR(128),error_code VARCHAR(64),next_attempt_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_notification_delivery(notification_id,channel,attempt_no),KEY ix_delivery_retry(status,next_attempt_at),
 FOREIGN KEY(notification_id) REFERENCES notification(id) ON DELETE RESTRICT,
 CHECK(attempt_no>0),CHECK(channel IN ('IN_APP','EMAIL','PUSH')),CHECK(status IN ('DELIVERED','FAILED','PENDING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 人工死信重放有独立租约和幂等记录；记录 actor/理由，不保存用户令牌。
CREATE TABLE message_replay (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT NOT NULL,delivery_scope VARCHAR(100) NOT NULL,
 request_key VARCHAR(64) NOT NULL,event_id CHAR(36) CHARACTER SET ascii NOT NULL,payload_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
 status VARCHAR(16) NOT NULL,claim_token CHAR(36) CHARACTER SET ascii NOT NULL,lease_until DATETIME(3) NOT NULL,
 reason VARCHAR(500) NOT NULL,trace_id VARCHAR(64) NOT NULL,response_body JSON,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_message_replay(actor_id,delivery_scope,request_key),CHECK(status IN ('IN_PROGRESS','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
