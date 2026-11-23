-- 索引删除补偿任务：文档删除时 ES/Milvus 清理失败落库，由 Worker 定时巡检排空，
-- 避免仅打印 warn 日志导致孤儿索引长期残留。
CREATE TABLE IF NOT EXISTS index_cleanup_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   VARCHAR(32)  NOT NULL COMMENT '租户ID',
    kb_id       BIGINT       NOT NULL COMMENT 'knowledge_base.id',
    document_id BIGINT       NOT NULL COMMENT 'document.id',
    target      VARCHAR(16)  NOT NULL COMMENT 'ES / MILVUS / BOTH',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE/FAILED',
    attempts    INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    error_msg   VARCHAR(512) DEFAULT NULL COMMENT '最近一次失败原因',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at),
    KEY idx_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='索引删除补偿任务';
