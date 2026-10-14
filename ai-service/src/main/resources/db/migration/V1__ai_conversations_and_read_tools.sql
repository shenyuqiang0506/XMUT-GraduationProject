-- AI 数据与身份/预约分库。owner_id 是跨服务用户标识，不建立跨数据库外键。
-- 模型和工具调用不持有会话行锁；短租约与条件版本阻止迟到结果覆盖新的消息。
CREATE TABLE ai_user_budget (
    owner_id BIGINT PRIMARY KEY,
    window_start DATETIME(3) NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_ai_budget CHECK (requests >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    create_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    normalized_conditions JSON NOT NULL,
    condition_version INT NOT NULL DEFAULT 0,
    processing_token CHAR(36) NULL,
    processing_until DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_ai_conversation_create (owner_id,create_key),
    KEY idx_ai_conversation_owner (owner_id,status,updated_at,id),
    KEY idx_ai_conversation_lease (processing_until),
    CONSTRAINT ck_ai_conversation_status CHECK (status IN ('ACTIVE','CLOSED')),
    CONSTRAINT ck_ai_conversation_mode CHECK (data_mode IN ('REAL','DEMO')),
    CONSTRAINT ck_ai_condition_version CHECK (condition_version >= 0),
    CONSTRAINT ck_ai_processing_lease CHECK ((processing_token IS NULL AND processing_until IS NULL) OR (processing_token IS NOT NULL AND processing_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一行保存用户消息及经过服务器校验的回答，不保存访问令牌或模型的内部推理内容。
CREATE TABLE ai_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    content TEXT NOT NULL,
    base_version INT NOT NULL,
    condition_version INT NULL,
    accepted_conditions JSON NULL,
    status VARCHAR(32) NOT NULL,
    result_json JSON NULL,
    claim_token CHAR(36) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_ai_client_message (conversation_id,client_message_id),
    UNIQUE KEY uq_ai_message_owner (conversation_id,id),
    KEY idx_ai_message_history (conversation_id,id),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_message_status CHECK (status IN ('PROCESSING','NEED_CLARIFICATION','READY','NO_MATCH','FAILED')),
    CONSTRAINT ck_ai_message_version CHECK (base_version >= 0 AND (condition_version IS NULL OR condition_version >= 0)),
    CONSTRAINT ck_ai_message_result CHECK ((status='PROCESSING' AND result_json IS NULL AND condition_version IS NULL) OR (status<>'PROCESSING' AND result_json IS NOT NULL AND condition_version IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_tool_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    input_arguments JSON NOT NULL,
    result_summary JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    duration_ms INT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_ai_tool_message (message_id,id),
    CONSTRAINT fk_ai_tool_message FOREIGN KEY (conversation_id,message_id) REFERENCES ai_message(conversation_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_tool_status CHECK (status IN ('SUCCEEDED','FAILED')),
    CONSTRAINT ck_ai_tool_duration CHECK (duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
