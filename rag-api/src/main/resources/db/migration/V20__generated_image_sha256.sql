-- AI 生图异步 SHA-256：generated_image 记录文件内容指纹（Worker 异步计算回写）
ALTER TABLE generated_image ADD COLUMN sha256 VARCHAR(64) NULL COMMENT '文件内容 SHA-256（hex，Worker 异步计算）' AFTER file_size;
CREATE INDEX idx_generated_image_sha256 ON generated_image (tenant_id, sha256);
