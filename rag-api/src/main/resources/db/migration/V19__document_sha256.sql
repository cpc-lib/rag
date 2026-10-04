-- 知识库文档秒传：document 记录 SHA-256，upload_session 支持 KB 业务（关联知识库ID）
ALTER TABLE document ADD COLUMN sha256 VARCHAR(64) NULL COMMENT '文件内容 SHA-256（hex，秒传匹配）' AFTER file_size;
CREATE INDEX idx_document_sha256 ON document (tenant_id, kb_id, sha256);

ALTER TABLE upload_session ADD COLUMN kb_id BIGINT NULL COMMENT '业务关联知识库ID（biz=KB_DOCUMENT 时必填）' AFTER biz;
