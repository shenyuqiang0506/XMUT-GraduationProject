-- M4 只新增迁移，不改动已执行的 V1/V2/V3；候补组保存固定时段和排序资源成员。
CREATE TABLE waitlist_group (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, group_key CHAR(64) CHARACTER SET ascii NOT NULL,
 start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL, data_mode VARCHAR(16) NOT NULL,
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_waitlist_group(group_key), CHECK(end_at>start_at), CHECK(version>=0), CHECK(data_mode IN ('REAL','DEMO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE waitlist_group_resource (
 group_id BIGINT NOT NULL, resource_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(group_id,resource_id), KEY ix_waitlist_group_resource(resource_id,group_id),
 FOREIGN KEY(group_id) REFERENCES waitlist_group(id) ON DELETE RESTRICT,
 FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE waitlist_entry (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, group_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL,
 activity_name VARCHAR(200) NOT NULL, description TEXT, participant_count INT NOT NULL,
 approval_group_id BIGINT NOT NULL, required_features JSON NOT NULL, applicant_snapshot JSON NOT NULL,
 policy_version INT NOT NULL, policy_snapshot JSON NOT NULL, status VARCHAR(24) NOT NULL,
 expires_at DATETIME(3) NOT NULL, offer_expires_at DATETIME(3), application_id BIGINT,
 last_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
 active_applicant BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('WAITING','OFFERED') THEN applicant_id ELSE NULL END) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_waitlist_entry_group(id,group_id), UNIQUE KEY uq_waitlist_active_user(group_id,active_applicant),
 UNIQUE KEY uq_waitlist_application(application_id),
 KEY ix_waitlist_fifo(group_id,status,created_at,id), KEY ix_waitlist_applicant(applicant_id,status),
 KEY ix_waitlist_deadline(status,expires_at), KEY ix_waitlist_offer(status,offer_expires_at),
 FOREIGN KEY(group_id) REFERENCES waitlist_group(id) ON DELETE RESTRICT,
 FOREIGN KEY(approval_group_id) REFERENCES approval_group(id) ON DELETE RESTRICT,
 FOREIGN KEY(application_id) REFERENCES activity_application(id) ON DELETE RESTRICT,
 CHECK(participant_count>0), CHECK(policy_version>0), CHECK(version>=0),
 CHECK(status IN ('WAITING','OFFERED','CONFIRMED','EXPIRED','CANCELLED')),
 CHECK((status='OFFERED' AND offer_expires_at IS NOT NULL) OR (status<>'OFFERED' AND offer_expires_at IS NULL)),
 CHECK((status='CONFIRMED' AND application_id IS NOT NULL) OR (status<>'CONFIRMED' AND application_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 查明 V3 自动命名的复合外键，避免依赖某台 MySQL 的默认约束名。
SET @application_member_fk=(SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='resource_occupancy' AND REFERENCED_TABLE_NAME='application_resource');
SET @drop_application_fk=CONCAT('ALTER TABLE resource_occupancy DROP FOREIGN KEY `',REPLACE(@application_member_fk,'`','``'),'`');
PREPARE drop_member_fk FROM @drop_application_fk;
EXECUTE drop_member_fk;
DEALLOCATE PREPARE drop_member_fk;
ALTER TABLE resource_occupancy MODIFY COLUMN application_id BIGINT NULL,
 ADD COLUMN waitlist_entry_id BIGINT NULL, ADD COLUMN waitlist_group_id BIGINT NULL,
 ADD UNIQUE KEY uq_waitlist_occupancy(waitlist_entry_id,resource_id),
 ADD CONSTRAINT fk_occupancy_application_member FOREIGN KEY(application_id,resource_id) REFERENCES application_resource(application_id,resource_id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_occupancy_waitlist_entry FOREIGN KEY(waitlist_entry_id,waitlist_group_id) REFERENCES waitlist_entry(id,group_id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_occupancy_waitlist_member FOREIGN KEY(waitlist_group_id,resource_id) REFERENCES waitlist_group_resource(group_id,resource_id) ON DELETE RESTRICT,
 ADD CONSTRAINT ck_occupancy_single_owner CHECK((application_id IS NOT NULL AND waitlist_entry_id IS NULL AND waitlist_group_id IS NULL)
    OR (application_id IS NULL AND waitlist_entry_id IS NOT NULL AND waitlist_group_id IS NOT NULL));

-- 老事件暂标 legacy，只有显式开启一次性接管时才归入当前部署 scope；自动验收不接管。
ALTER TABLE outbox_event ADD COLUMN delivery_scope VARCHAR(100) NOT NULL DEFAULT 'legacy',
 ADD COLUMN last_error_code VARCHAR(64), ADD COLUMN dead_lettered_at DATETIME(3),
 ADD COLUMN dead_letter_attempts INT NOT NULL DEFAULT 0, ADD COLUMN version INT NOT NULL DEFAULT 0,
 ADD KEY ix_outbox_scope_delivery(delivery_scope,status,next_attempt_at),
 ADD CHECK(dead_letter_attempts>=0), ADD CHECK(version>=0);
