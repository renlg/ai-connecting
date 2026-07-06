-- 数据库迁移脚本：将 channels 表的 models 字段从模型名称转换为模型ID
-- 执行前请备份数据库！

-- 1. 添加新字段 modelIds
ALTER TABLE channels ADD COLUMN modelIds VARCHAR(2000);

-- 2. 数据迁移：将 models 中的模型名称转换为对应的模型ID
-- 使用子查询和 GROUP_CONCAT 进行转换
UPDATE channels 
SET modelIds = (
    SELECT GROUP_CONCAT(mc.id, ',')
    FROM model_configs mc
    WHERE mc.name IN (
        SELECT DISTINCT TRIM(model_name)
        FROM (
            -- 将逗号分隔的模型名称拆分为多行
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

-- 3. 删除旧字段 models（可选，建议先验证数据后再执行）
-- ALTER TABLE channels DROP COLUMN models;

-- 4. 验证迁移结果
SELECT id, name, models, modelIds FROM channels WHERE models IS NOT NULL OR modelIds IS NOT NULL;
