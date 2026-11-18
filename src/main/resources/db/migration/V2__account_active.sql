-- V2：账号启用 / 停用（FR-007～FR-016、FR-021）。只增不删；不读写任何业务行（TD-022、TD-029）。
-- V1 既有账号在加列时取默认值 TRUE（全部启用）；期望解决时间由 submitted_at 与 urgency 派生，不落库（TD-020）。
-- 已提交的迁移文件永不修改。
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
