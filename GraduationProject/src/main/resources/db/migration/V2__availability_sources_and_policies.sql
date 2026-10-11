-- 可用性基础独立迁移：不修改已经执行的 V1，也不预置未经学校确认的规则。
CREATE TABLE booking_policy_scope (
 scope_code VARCHAR(64) NOT NULL PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CHECK(scope_code IN ('REAL','DEMO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO booking_policy_scope(scope_code) VALUES ('REAL'),('DEMO');

CREATE TABLE booking_policy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 scope_code VARCHAR(64) NOT NULL,policy_version INT NOT NULL,settings JSON NOT NULL,
 basis VARCHAR(500) NOT NULL,active TINYINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 -- 非生效版本为 NULL，唯一索引保证同一范围最多有一份当前规则。
 active_scope VARCHAR(64) GENERATED ALWAYS AS (IF(active=1,scope_code,NULL)) STORED,
 UNIQUE KEY uq_policy_version(scope_code,policy_version),UNIQUE KEY uq_policy_active(active_scope),
 FOREIGN KEY(scope_code) REFERENCES booking_policy_scope(scope_code) ON DELETE RESTRICT,
 CHECK(policy_version>0),CHECK(active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE availability_coverage (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,resource_id BIGINT NOT NULL,
 source_system VARCHAR(32) NOT NULL,from_at DATETIME(3) NOT NULL,to_at DATETIME(3) NOT NULL,
 checked_at DATETIME(3) NOT NULL,completeness VARCHAR(16) NOT NULL,
 import_batch_id BIGINT NOT NULL,data_mode VARCHAR(16) NOT NULL,active TINYINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY ix_coverage_resource(resource_id,active,from_at,to_at),KEY ix_coverage_batch(import_batch_id),
 FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE RESTRICT,
 FOREIGN KEY(import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
 CHECK(to_at>from_at),CHECK(completeness IN ('COMPLETE','PARTIAL')),
 CHECK(data_mode IN ('REAL','DEMO')),CHECK(active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_occupancy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,resource_id BIGINT NOT NULL,
 source_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 start_at DATETIME(3) NOT NULL,end_at DATETIME(3) NOT NULL,reason_code VARCHAR(32) NOT NULL,
 source_system VARCHAR(32) NOT NULL,import_batch_id BIGINT,
 observed_at DATETIME(3) NOT NULL,active TINYINT NOT NULL DEFAULT 1,data_mode VARCHAR(16) NOT NULL,
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_external_source(source_system,source_key,resource_id,data_mode),
 KEY ix_external_resource(resource_id,active,start_at,end_at),KEY ix_external_batch(import_batch_id),
 FOREIGN KEY(resource_id) REFERENCES resource(id) ON DELETE RESTRICT,
 FOREIGN KEY(import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
 CHECK(end_at>start_at),CHECK(active IN (0,1)),CHECK(data_mode IN ('REAL','DEMO')),
 CHECK(version>=0),CHECK(reason_code IN ('COURSE','EXAM','MAINTENANCE','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
