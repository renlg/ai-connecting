-- ============================================================
-- ai-connecting 数据库完整表结构（MySQL，可选，多实例部署用）
-- 由 MysqlSchemaInitializer 在 Hibernate ddl-auto=validate 之前执行，
-- 所有语句幂等（CREATE TABLE IF NOT EXISTS / 索引重复创建时由初始化器忽略 Duplicate key name 错误）
-- 列结构与当前 JPA 实体保持一致（非 schema.sql 中遗留的历史快照）
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    email VARCHAR(200),
    role VARCHAR(20) NOT NULL,
    status INT NOT NULL,
    quota BIGINT NOT NULL,
    used_quota BIGINT NOT NULL,
    credits DECIMAL(19,4) NOT NULL DEFAULT 0,
    invite_code VARCHAR(16),
    invite_code_used BOOLEAN NOT NULL DEFAULT FALSE,
    level INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_invite_code (invite_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- OAuth2 client registry. client_secret is plaintext for the current internal-only integration.
CREATE TABLE IF NOT EXISTS oauth_clients (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    client_secret VARCHAR(255) NOT NULL,
    redirect_uri VARCHAR(1000) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_oauth_clients_client_id (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Short-lived, single-use OAuth2 authorization codes.
CREATE TABLE IF NOT EXISTS oauth_codes (
    code VARCHAR(64) PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    redirect_uri VARCHAR(1000) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Token 管理表
CREATE TABLE IF NOT EXISTS tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_key VARCHAR(100) NOT NULL,
    allowed_models VARCHAR(2000),
    quota BIGINT NOT NULL,
    used_quota BIGINT NOT NULL,
    credits DECIMAL(19,4) NOT NULL DEFAULT -1,
    rate_limit INT DEFAULT 0,
    status INT NOT NULL,
    expired_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    UNIQUE KEY uk_tokens_token_key (token_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 渠道表（列名与 Channel 实体隐式命名策略一致：modelIds -> model_ids, supportedLevels -> supported_levels）
CREATE TABLE IF NOT EXISTS channels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(1000) NOT NULL,
    model_ids VARCHAR(2000),
    model_mapping TEXT,
    supported_levels VARCHAR(50),
    status INT NOT NULL,
    priority INT NOT NULL,
    used_quota BIGINT NOT NULL,
    rate_limit INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 模型配置表
CREATE TABLE IF NOT EXISTS model_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    type VARCHAR(20) NOT NULL DEFAULT 'text',
    input_credit_rate INT NOT NULL,
    output_credit_rate INT NOT NULL,
    description VARCHAR(500),
    status INT NOT NULL,
    admin_only BOOLEAN NOT NULL DEFAULT FALSE,
    cache_credit_rate DECIMAL(10,2) NOT NULL,
    image_price_1k DECIMAL(10,2) NOT NULL DEFAULT 0,
    image_price_2k DECIMAL(10,2) NOT NULL DEFAULT 0,
    image_price_4k DECIMAL(10,2) NOT NULL DEFAULT 0,
    video_price_480p DECIMAL(10,2) NOT NULL DEFAULT 0,
    video_price_720p DECIMAL(10,2) NOT NULL DEFAULT 0,
    video_price_1080p DECIMAL(10,2) NOT NULL DEFAULT 0,
    video_price_4k DECIMAL(10,2) NOT NULL DEFAULT 0,
    audio_price_standard DECIMAL(10,2) NOT NULL DEFAULT 0,
    audio_price_hd DECIMAL(10,2) NOT NULL DEFAULT 0,
    fallback_group_id BIGINT,
    supported_levels VARCHAR(50),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 模型组：面向客户端的公开别名，绑定同类型多个成员模型，按策略选择并在上游失败时自动切换成员
CREATE TABLE IF NOT EXISTS model_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    strategy VARCHAR(20) NOT NULL DEFAULT 'round_robin',
    max_attempts INT NOT NULL DEFAULT 5,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    admin_only BOOLEAN NOT NULL DEFAULT FALSE,
    input_price DECIMAL(10,4),
    output_price DECIMAL(10,4),
    cached_price DECIMAL(10,4),
    price_1k DECIMAL(10,2),
    price_2k DECIMAL(10,2),
    price_4k DECIMAL(10,2),
    price_480p DECIMAL(10,2),
    price_720p DECIMAL(10,2),
    price_1080p DECIMAL(10,2),
    video_price_4k DECIMAL(10,2),
    price_standard DECIMAL(10,2),
    price_hd DECIMAL(10,2),
    supported_levels VARCHAR(50),
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_model_groups_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 模型组成员：模型组与成员模型 (model_configs) 的关联行，携带权重与排序
CREATE TABLE IF NOT EXISTS model_group_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    model_config_id BIGINT NOT NULL,
    weight INT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_model_group_members_group_id ON model_group_members (group_id);
CREATE INDEX idx_model_group_members_model_config_id ON model_group_members (model_config_id);

-- 使用记录表（credit_cost 修正为 DECIMAL(19,6) NOT NULL DEFAULT 0，而非历史快照中的 BIGINT）
CREATE TABLE IF NOT EXISTS usage_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_id BIGINT,
    channel_id BIGINT,
    model VARCHAR(100),
    actual_model VARCHAR(100),
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    prompt_tokens_cache_hit INT NOT NULL DEFAULT 0,
    cached_tokens_cache_creation INT NOT NULL DEFAULT 0,
    cached_tokens_cache_read INT NOT NULL DEFAULT 0,
    ip VARCHAR(50),
    duration BIGINT,
    credit_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
    request_path VARCHAR(500),
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_usage_logs_token_id ON usage_logs (token_id);
CREATE INDEX idx_usage_logs_channel_id ON usage_logs (channel_id);
CREATE INDEX idx_usage_logs_created_at ON usage_logs (created_at);
CREATE INDEX idx_usage_logs_token_created ON usage_logs (token_id, created_at);
CREATE INDEX idx_usage_logs_model_created ON usage_logs (model, created_at);

-- 终端用户请求失败日志（仅保留 7 天）
CREATE TABLE IF NOT EXISTS failure_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    user_error VARCHAR(2000) NOT NULL,
    channel_error VARCHAR(2000),
    http_status INT NOT NULL,
    model_name VARCHAR(100),
    channel_model_name VARCHAR(100),
    channel_name VARCHAR(100),
    protocol VARCHAR(20),
    created_at BIGINT NOT NULL,
    KEY idx_failure_logs_trace_id (trace_id),
    KEY idx_failure_logs_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 使用统计汇总表（15分钟预聚合）
CREATE TABLE IF NOT EXISTS usage_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date VARCHAR(10) NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    total_requests BIGINT NOT NULL,
    total_prompt_tokens BIGINT NOT NULL,
    total_completion_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    total_cached_prompt_tokens BIGINT NOT NULL,
    total_cache_creation_tokens BIGINT NOT NULL,
    total_cache_read_tokens BIGINT NOT NULL,
    total_credit_cost DECIMAL(19,6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_usage_stats_window (start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_usage_stats_start_time ON usage_stats (start_time);
CREATE INDEX idx_usage_stats_date ON usage_stats (date);

-- 公告表
CREATE TABLE IF NOT EXISTS announcements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status INT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 优惠券表
CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    credits DECIMAL(19,4) NOT NULL DEFAULT 0,
    max_uses INT NOT NULL,
    used_count INT NOT NULL,
    expiry_date DATETIME(6),
    created_by BIGINT NOT NULL,
    status INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    UNIQUE KEY uk_coupons_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 优惠券兑换记录表
CREATE TABLE IF NOT EXISTS coupon_redemption_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    redeemed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_coupon_redemption_coupon_user (coupon_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 操作日志表（管理后台审计日志）
CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    target VARCHAR(200),
    detail VARCHAR(2000),
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_operation_logs_admin_id ON operation_logs (admin_id);
CREATE INDEX idx_operation_logs_created_at ON operation_logs (created_at);

-- 视频生成任务表（含预扣/结算/对账租约字段，与 VideoTask 实体当前全部字段一致）
CREATE TABLE IF NOT EXISTS video_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    upstream_id VARCHAR(200) NOT NULL,
    channel_id BIGINT NOT NULL,
    token_id BIGINT,
    user_id BIGINT NOT NULL,
    model VARCHAR(100),
    prepaid_cost DECIMAL(10,2),
    deducted BOOLEAN NOT NULL DEFAULT FALSE,
    size VARCHAR(50),
    duration_seconds INT,
    unit_price DECIMAL(10,4),
    usage_log_id BIGINT,
    settled BOOLEAN NOT NULL DEFAULT FALSE,
    oss_url VARCHAR(500),
    next_reconcile_at DATETIME(6),
    processing_lease_until DATETIME(6),
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_video_tasks_upstream_id ON video_tasks (upstream_id);

-- 风险管理策略表（按渠道+模型维度配置速率限制与熔断时长）
CREATE TABLE IF NOT EXISTS risk_policies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    model_config_name VARCHAR(100),
    rate_limit INT NOT NULL,
    time_window VARCHAR(20) NOT NULL,
    window_type VARCHAR(20) NOT NULL DEFAULT 'SLIDING',
    circuit_breaker_duration INT NOT NULL,
    status INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_risk_policies_channel_id ON risk_policies (channel_id);
CREATE INDEX idx_risk_policies_status ON risk_policies (status);

-- 失败策略表（按全局/渠道维度配置失败计数与熔断）
CREATE TABLE IF NOT EXISTS failure_strategies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(20) NOT NULL,
    channel_id BIGINT,
    model_config_id BIGINT,
    http_codes VARCHAR(200) NOT NULL,
    window_type VARCHAR(20) NOT NULL DEFAULT 'SLIDING',
    window_dimension VARCHAR(20) NOT NULL,
    failure_threshold INT NOT NULL,
    fuse_duration_seconds INT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_failure_strategies_scope ON failure_strategies (scope);
CREATE INDEX idx_failure_strategies_enabled ON failure_strategies (enabled);

-- 熔断记录表（策略触发或手动添加后写入，记录熔断时间段与状态）
CREATE TABLE IF NOT EXISTS circuit_breaker_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id BIGINT,
    channel_id BIGINT NOT NULL,
    model_config_name VARCHAR(100),
    source VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    triggered_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_circuit_breaker_records_channel_id ON circuit_breaker_records (channel_id);
CREATE INDEX idx_circuit_breaker_records_status_expires ON circuit_breaker_records (status, expires_at);
CREATE INDEX idx_circuit_breaker_records_triggered_at ON circuit_breaker_records (triggered_at);

-- 渠道失败记录表（AI分析用，保留3天）
CREATE TABLE IF NOT EXISTS channel_failure_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    channel_name VARCHAR(100),
    model_name VARCHAR(200),
    error_code VARCHAR(50),
    error_message VARCHAR(500),
    analyzed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_channel_failure_records_created_at ON channel_failure_records (created_at);
CREATE INDEX idx_channel_failure_records_analyzed_created ON channel_failure_records (analyzed, created_at);
