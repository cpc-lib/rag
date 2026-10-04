-- 秒传支持：library_file / upload_session 记录文件 SHA-256，同租户同文件重复上传直接复用已有条目
ALTER TABLE library_file ADD COLUMN sha256 VARCHAR(64) NULL COMMENT '文件内容 SHA-256（hex，分片上传写入，用于秒传）' AFTER file_size;
CREATE INDEX idx_library_file_sha256 ON library_file (tenant_id, sha256);

ALTER TABLE upload_session ADD COLUMN sha256 VARCHAR(64) NULL COMMENT '文件内容 SHA-256（hex，init 时前端上送）' AFTER content_type;
