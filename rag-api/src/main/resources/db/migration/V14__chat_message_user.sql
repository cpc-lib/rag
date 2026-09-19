-- 消息表补用户归属：新消息直接写入；存量消息按所属会话回填，
-- V9 前 session_id 为空的旧留痕无法归属，保持 NULL。
ALTER TABLE chat_message ADD COLUMN user_id BIGINT AFTER tenant_id;
UPDATE chat_message m
JOIN chat_session s ON m.session_id = s.id
SET m.user_id = s.user_id;
ALTER TABLE chat_message ADD INDEX idx_tenant_user (tenant_id, user_id);
