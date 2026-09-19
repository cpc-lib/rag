-- 多会话管理：问答按会话(chat_session)组织，同会话消息全部落库并作为历史上下文。
CREATE TABLE chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  VARCHAR(64)  NOT NULL,
    kb_id      BIGINT       NOT NULL,
    title      VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_updated (tenant_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 消息归属会话；V9 之前的历史问答不归属任何会话（保留留痕，session_id 为 NULL）
ALTER TABLE chat_message ADD COLUMN session_id BIGINT AFTER tenant_id;
