-- ============================================================
-- ai-connecting 数据库迁移总脚本
-- 合并以下脚本：
--   migrate_user_levels.sql
--   migrate_models_to_ids.sql
--   migrate_cache_tokens_and_multiplier.sql
-- 执行顺序按原始创建时间排列
-- 注意：本脚本非幂等，重复执行会因列已存在而报错
-- 执行前请备份数据库！
-- ============================================================

-- ============================================================
-- Part 1: 用户等级 / 渠道支持等级
-- ============================================================

-- 用户新增 level 字段 (1-5)，默认为 1
ALTER TABLE users ADD COLUMN level INTEGER NOT NULL DEFAULT 1;

-- 渠道新增 supportedLevels 字段（逗号分隔），默认支持所有等级
ALTER TABLE channels ADD COLUMN supportedLevels VARCHAR(50) DEFAULT '1,2,3,4,5';


-- ============================================================
-- Part 2: channels 表 models -> modelIds 迁移
-- ============================================================

-- 1. 添加新字段 modelIds
ALTER TABLE channels ADD COLUMN modelIds VARCHAR(2000);

-- 2. 数据迁移：将 models 中的模型名称转换为对应的模型ID
UPDATE channels 
SET modelIds = (
    SELECT GROUP_CONCAT(mc.id, ',')
    FROM model_configs mc
    WHERE mc.name IN (
        SELECT DISTINCT TRIM(model_name)
        FROM (
            WITH RECURSIVE split(id, model_name, rest) AS (
                SELECT 
                    c.id,
                    CASE 
                        WHEN INSTR(c.models || ',', ',') > 0 THEN SUBSTR(c.models || ',', 1, INSTR(c.models || ',', ',') - 1)
                        ELSE c.models
                    END,
                    CASE 
                        WHEN INSTR(c.models || ',', ',') > 0 THEN SUBSTR(c.models || ',', INSTR(c.models || ',', ',') + 1)
                        ELSE ''
                    END
                FROM channels c
                WHERE c.models IS NOT NULL AND c.models != ''
                
                UNION ALL
                
                SELECT 
                    id,
                    CASE 
                        WHEN INSTR(rest, ',') > 0 THEN SUBSTR(rest, 1, INSTR(rest, ',') - 1)
                        ELSE rest
                    END,
                    CASE 
                        WHEN INSTR(rest, ',') > 0 THEN SUBSTR(rest, INSTR(rest, ',') + 1)
                        ELSE ''
                    END
                FROM split
                WHERE rest != ''
            )
            SELECT TRIM(model_name) as model_name, id
            FROM split
            WHERE model_name != ''
        ) AS models_list
        WHERE models_list.id = channels.id
    )
    AND EXISTS (
        SELECT 1 FROM model_configs mc2 
        WHERE mc2.name = models_list.model_name
    )
)
WHERE models IS NOT NULL AND models != '';


-- ============================================================
-- Part 3: 缓存 Token / 倍率字段
-- ============================================================

-- usage_logs 表新增缓存 Token 字段
ALTER TABLE usage_logs ADD COLUMN prompt_tokens_cache_hit INTEGER;
ALTER TABLE usage_logs ADD COLUMN cached_tokens_cache_creation INTEGER;
ALTER TABLE usage_logs ADD COLUMN cached_tokens_cache_read INTEGER;

-- model_configs 表新增倍率字段
ALTER TABLE model_configs ADD COLUMN multiplier DECIMAL(10, 2) NOT NULL DEFAULT 1.0;
