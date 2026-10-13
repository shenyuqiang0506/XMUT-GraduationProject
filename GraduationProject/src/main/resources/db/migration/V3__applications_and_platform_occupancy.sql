-- 联合申请闭环：不修改已执行的 V1/V2，用户 ID 为跨身份库逻辑引用。
-- 协调行保证来源/规则变化与整组申请状态调整不交叉，资源成员仍按 ID 升序加锁。
CREATE TABLE booking_write_guard (
 id TINYINT NOT NULL PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CHECK(id=1)
) ENGINE=InnoDB;
INSERT INTO booking_write_guard(id) VALUES(1);

CREATE TABLE applicant_guard (
 user_id BIGINT NOT NULL PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE activity_application (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, applicant_id BIGINT NOT NULL,
 activity_name VARCHAR(200) NOT NULL, description TEXT, participant_count INT NOT NULL,
 start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL, status VARCHAR(32) NOT NULL,
 approval_group_id BIGINT NOT NULL, hold_expires_at DATETIME(3), policy_version INT NOT NULL,
 policy_snapshot JSON NOT NULL, applicant_snapshot JSON NOT NULL, required_features JSON NOT NULL,
 data_mode VARCHAR(16) NOT NULL, last_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY ix_application_applicant(applicant_id,status,created_at),
 KEY ix_application_group(approval_group_id,status,created_at),
 KEY ix_application_expiry(status,hold_expires_at), KEY ix_application_end(status,end_at),
 FOREIGN KEY(approval_group_id) REFERENCES approval_group(id) ON DELETE RESTRICT,
 CHECK(participant_count>0), CHECK(end_at>start_at), CHECK(version>=0), CHECK(policy_version>0),
 CHECK(data_mode IN ('REAL','DEMO')),
 CHECK(status IN ('PENDING_APPROVAL','APPROVED','CONFLICT_REVIEW','COMPLETED','REJECTED','EXPIRED','CANCELLED')),
 CHECK((status='PENDING_APPROVAL' AND hold_expires_at IS NOT NULL) OR (status<>'PENDING_APPROVAL' AND hold_expires_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application_resource (
 application_id BIGINT NOT NULL, resource_id BIGINT NOT NULL, resource_snapshot JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(application_id,resource_id), KEY ix_application_resource(resource_id,application_id),
 FOREIGN KEY(application_id) REFERENCES activity_application(id) ON DELETE RESTRICT,
 FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_occupancy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, resource_id BIGINT NOT NULL, application_id BIGINT NOT NULL,
 start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL, status VARCHAR(16) NOT NULL,
 expires_at DATETIME(3), released_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_application_occupancy(application_id,resource_id),
 KEY ix_resource_occupancy(resource_id,status,start_at,end_at), KEY ix_occupancy_expiry(status,expires_at),
 FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE RESTRICT,
 FOREIGN KEY(application_id,resource_id) REFERENCES application_resource(application_id,resource_id) ON DELETE RESTRICT,
 CHECK(end_at>start_at), CHECK(status IN ('HOLD','CONFIRMED','RELEASED')),
 CHECK((status='HOLD' AND expires_at IS NOT NULL AND released_at IS NULL)
    OR (status='CONFIRMED' AND expires_at IS NULL AND released_at IS NULL)
    OR (status='RELEASED' AND released_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, application_id BIGINT NOT NULL, operator_id BIGINT,
 decision VARCHAR(32) NOT NULL, reason VARCHAR(500), from_version INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_application_decision(application_id,from_version), KEY ix_approval_operator(operator_id),
 FOREIGN KEY(application_id) REFERENCES activity_application(id) ON DELETE RESTRICT, CHECK(from_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 本阶段只持久化状态事件；RabbitMQ 投递、候补和通知消费者留给 M4。
CREATE TABLE outbox_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_id CHAR(36) CHARACTER SET ascii NOT NULL,
 event_type VARCHAR(64) NOT NULL, aggregate_type VARCHAR(32) NOT NULL, aggregate_id BIGINT NOT NULL,
 aggregate_version INT NOT NULL, payload JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 claim_token CHAR(36) CHARACTER SET ascii, claim_expires_at DATETIME(3), published_at DATETIME(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_outbox_event(event_id), UNIQUE KEY uq_application_event(aggregate_type,aggregate_id,aggregate_version),
 KEY ix_outbox_delivery(status,next_attempt_at), KEY ix_outbox_claim(claim_expires_at),
 CHECK(status IN ('PENDING','SENDING','SENT','FAILED')), CHECK(attempts>=0), CHECK(aggregate_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
