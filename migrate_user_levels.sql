-- 用户等级/渠道支持等级 迁移脚本
-- 注意：本脚本非幂等，仅可执行一次，重复执行会因列已存在而报错
-- 用户新增 level 字段 (1-5)，默认为 1
ALTER TABLE users ADD COLUMN level INTEGER NOT NULL DEFAULT 1;

-- 渠道新增 supportedLevels 字段（逗号分隔），默认支持所有等级
ALTER TABLE channels ADD COLUMN supportedLevels VARCHAR(50) DEFAULT '1,2,3,4,5';
