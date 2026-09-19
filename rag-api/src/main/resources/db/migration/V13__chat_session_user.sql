-- 会话按用户隔离：同租户不同用户互不可见。
ALTER TABLE chat_session ADD COLUMN user_id BIGINT AFTER tenant_id;
-- 存量会话无归属信息（消息表亦无 user_id 可反查）：清除已串的孤儿会话及其留痕。
DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session);
DELETE FROM chat_session;
ALTER TABLE chat_session MODIFY COLUMN user_id BIGINT NOT NULL;
-- 查询模式为 tenant_id + user_id 按 updated_at 排序：替换旧的仅租户索引。
ALTER TABLE chat_session DROP INDEX idx_tenant_updated;
ALTER TABLE chat_session ADD INDEX idx_tenant_user_updated (tenant_id, user_id, updated_at);
