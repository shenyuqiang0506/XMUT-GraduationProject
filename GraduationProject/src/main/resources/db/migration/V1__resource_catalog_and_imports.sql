CREATE TABLE approval_group
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_approval_group_name(name),
    CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE approval_group_member
(
    group_id   BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_id, user_id),
    KEY        ix_group_member_user(user_id),
    FOREIGN KEY (group_id) REFERENCES approval_group (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE import_batch
(
    id               BIGINT                       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_system    VARCHAR(32)                  NOT NULL,
    import_type      VARCHAR(32)                  NOT NULL,
    data_mode        VARCHAR(16)                  NOT NULL,
    filename         VARCHAR(255)                 NOT NULL,
    file_storage_key VARCHAR(500)                 NOT NULL,
    file_sha256      CHAR(64) CHARACTER SET ascii NOT NULL,
    status           VARCHAR(24)                  NOT NULL,
    operator_id      BIGINT                       NOT NULL,
    field_mapping    JSON                         NOT NULL,
    preview_result   JSON,
    committed_at     DATETIME(3),
    query_context    JSON,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY              ix_import_status(status,created_at),
    KEY              ix_import_source(source_system,file_sha256),
    CHECK (status IN ('PREVIEWED', 'COMMITTED', 'FAILED')),
    CHECK (data_mode IN ('REAL', 'DEMO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE resource
(
    id                BIGINT                          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_type     VARCHAR(16)                     NOT NULL,
    source_system     VARCHAR(32)                     NOT NULL,
    source_id         VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    name              VARCHAR(200)                    NOT NULL,
    campus            VARCHAR(100),
    building          VARCHAR(100),
    floor_no          VARCHAR(16),
    category          VARCHAR(100),
    subcategory       VARCHAR(100),
    capacity          INT,
    exam_capacity     INT,
    area              DECIMAL(10, 2),
    source_borrowable VARCHAR(16)                     NOT NULL,
    platform_status   VARCHAR(16)                     NOT NULL DEFAULT 'DRAFT',
    approval_group_id BIGINT,
    data_mode         VARCHAR(16)                     NOT NULL,
    remark            TEXT,
    raw_payload       JSON,
    import_batch_id   BIGINT,
    version           INT                             NOT NULL DEFAULT 0,
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_resource_source(source_system,source_id,data_mode),
    KEY               ix_resource_catalog(platform_status,resource_type,campus),
    KEY               ix_resource_group(approval_group_id),
    KEY               ix_resource_batch(import_batch_id),
    FOREIGN KEY (approval_group_id) REFERENCES approval_group (id) ON DELETE RESTRICT,
    FOREIGN KEY (import_batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT,
    CHECK (resource_type IN ('VENUE', 'EQUIPMENT')),
    CHECK (source_borrowable IN ('YES', 'NO', 'UNKNOWN')),
    CHECK (platform_status IN ('DRAFT', 'OPEN', 'CLOSED', 'RETIRED')),
    CHECK (data_mode IN ('REAL', 'DEMO')),
    CHECK (capacity >= 0),
    CHECK (exam_capacity >= 0),
    CHECK (area >= 0),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE resource_manager
(
    resource_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (resource_id, user_id),
    KEY         ix_resource_manager_user(user_id),
    FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE resource_feature
(
    resource_id   BIGINT       NOT NULL,
    feature_code  VARCHAR(32)  NOT NULL,
    feature_value VARCHAR(200) NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (resource_id, feature_code),
    KEY           ix_resource_feature(feature_code,resource_id),
    FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE equipment_detail
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id          BIGINT      NOT NULL,
    asset_code           VARCHAR(64) NOT NULL,
    equipment_type       VARCHAR(32) NOT NULL,
    condition_status     VARCHAR(16) NOT NULL,
    location_resource_id BIGINT,
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_equipment_resource(resource_id),
    UNIQUE KEY uq_equipment_asset(asset_code),
    KEY                  ix_equipment_location(location_resource_id),
    FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE RESTRICT,
    FOREIGN KEY (location_resource_id) REFERENCES resource (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE resource_opening_rule
(
    id          BIGINT  NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT  NOT NULL,
    weekday     TINYINT NOT NULL,
    start_local TIME    NOT NULL,
    end_local   TIME    NOT NULL,
    valid_from  DATE    NOT NULL,
    valid_to    DATE    NOT NULL,
    enabled     TINYINT NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY         ix_opening_rule(resource_id,weekday,valid_from),
    FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE RESTRICT,
    CHECK (weekday BETWEEN 1 AND 7),
    CHECK (end_local > start_local),
    CHECK (valid_to >= valid_from),
    CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE import_error
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id     BIGINT       NOT NULL,
    `row_number` INT          NOT NULL,
    field_name   VARCHAR(64),
    error_code   VARCHAR(64)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY          ix_import_error(batch_id,`row_number`),
    FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE idempotency_record
(
    id            BIGINT                          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id      BIGINT                          NOT NULL,
    operation     VARCHAR(64)                     NOT NULL,
    request_key   VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payload_hash  CHAR(64) CHARACTER SET ascii    NOT NULL,
    status        VARCHAR(16)                     NOT NULL,
    http_status   INT,
    response_body JSON,
    expires_at    DATETIME(3) NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_booking_idempotency(actor_id,operation,request_key),
    KEY           ix_booking_idempotency_expiry(expires_at),
    CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE audit_log
(
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id    BIGINT,
    action      VARCHAR(64) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id   BIGINT      NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    reason      VARCHAR(500),
    trace_id    VARCHAR(64) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY         ix_booking_audit_object(object_type,object_id,created_at),
    KEY         ix_booking_audit_actor(actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
