-- 添加缓存 Token 相关字段到 usage_logs 表
ALTER TABLE usage_logs ADD COLUMN prompt_tokens_cache_hit INTEGER;
ALTER TABLE usage_logs ADD COLUMN cached_tokens_cache_creation INTEGER;
ALTER TABLE usage_logs ADD COLUMN cached_tokens_cache_read INTEGER;

-- 添加倍率字段到 model_configs 表
ALTER TABLE model_configs ADD COLUMN multiplier DECIMAL(10, 2) NOT NULL DEFAULT 1.0;
