-- ============================================================
-- ai-connecting 数据库完整表结构（SQLite）
-- 不含数据，仅 DDL
-- 生成时间: 2026-07-16
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS "users" (
    id INTEGER PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    email VARCHAR(200),
    role VARCHAR(20) NOT NULL,
    status INTEGER NOT NULL,
    quota BIGINT NOT NULL,
    used_quota BIGINT NOT NULL,
    credits INTEGER NOT NULL DEFAULT 0,
    invite_code VARCHAR(16),
    level INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- Token 管理表
CREATE TABLE IF NOT EXISTS "tokens" (
    id INTEGER PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_key VARCHAR(100) NOT NULL UNIQUE,
    allowed_models VARCHAR(2000),
    quota BIGINT NOT NULL,
    used_quota BIGINT NOT NULL,
    credits REAL DEFAULT -1.0 NOT NULL,
    rate_limit INTEGER DEFAULT 0,
    status INTEGER NOT NULL,
    expired_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- 渠道表
CREATE TABLE IF NOT EXISTS "channels" (
    id INTEGER PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(1000) NOT NULL,
    models VARCHAR(2000),
    model_ids VARCHAR(2000),
    modelIds VARCHAR(2000),
    priority INTEGER NOT NULL,
    rate_limit INTEGER NOT NULL,
    status INTEGER NOT NULL,
    used_quota BIGINT NOT NULL,
    credits REAL DEFAULT -1.0 NOT NULL,
    supported_levels VARCHAR(50),
    supportedLevels VARCHAR(50) DEFAULT '1,2,3,4,5',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- 模型配置表
CREATE TABLE IF NOT EXISTS "model_configs" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    description VARCHAR(500),
    status INTEGER NOT NULL,
    input_credit_rate INTEGER NOT NULL DEFAULT 0,
    output_credit_rate INTEGER NOT NULL DEFAULT 0,
    cache_credit_rate DECIMAL(5,4) NOT NULL DEFAULT 0.1,
    admin_only BOOLEAN DEFAULT 0,
    multiplier DECIMAL(10, 2) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- 使用记录表
CREATE TABLE IF NOT EXISTS "usage_logs" (
    id INTEGER PRIMARY KEY,
    token_id BIGINT,
    channel_id BIGINT,
    model VARCHAR(100),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    prompt_tokens_cache_hit INTEGER,
    cached_tokens_cache_creation INTEGER,
    cached_tokens_cache_read INTEGER,
    credit_cost BIGINT DEFAULT 0,
    request_path VARCHAR(500),
    ip VARCHAR(50),
    duration BIGINT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_logs_token_id ON usage_logs (token_id);
CREATE INDEX IF NOT EXISTS idx_usage_logs_channel_id ON usage_logs (channel_id);
CREATE INDEX IF NOT EXISTS idx_usage_logs_created_at ON usage_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_usage_logs_token_created ON usage_logs (token_id, created_at);

-- 使用统计汇总表（15分钟预聚合）
CREATE TABLE IF NOT EXISTS "usage_stats" (
    id INTEGER PRIMARY KEY,
    date VARCHAR(10) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    total_requests BIGINT NOT NULL,
    total_prompt_tokens BIGINT NOT NULL,
    total_completion_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    total_cached_prompt_tokens BIGINT NOT NULL,
    total_cache_creation_tokens BIGINT NOT NULL,
    total_cache_read_tokens BIGINT NOT NULL,
    total_credit_cost NUMERIC(19,6) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_stats_start_time ON usage_stats (start_time);
CREATE INDEX IF NOT EXISTS idx_usage_stats_date ON usage_stats (date);

-- 公告表
CREATE TABLE IF NOT EXISTS "announcements" (
    id INTEGER PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status INTEGER NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- 优惠券表
CREATE TABLE IF NOT EXISTS "coupons" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code VARCHAR(40) NOT NULL UNIQUE,
    credits DOUBLE NOT NULL,
    max_uses INTEGER NOT NULL,
    used_count INTEGER NOT NULL,
    status INTEGER NOT NULL,
    expiry_date TIMESTAMP,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- 优惠券兑换记录表
CREATE TABLE IF NOT EXISTS "coupon_redemption_logs" (
    id INTEGER PRIMARY KEY,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    redeemed_at TIMESTAMP NOT NULL
);

-- 操作日志表（管理后台审计日志）
CREATE TABLE IF NOT EXISTS "operation_logs" (
    id INTEGER PRIMARY KEY,
    admin_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    target VARCHAR(200),
    detail VARCHAR(2000),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_operation_logs_admin_id ON operation_logs (admin_id);
CREATE INDEX IF NOT EXISTS idx_operation_logs_created_at ON operation_logs (created_at);
