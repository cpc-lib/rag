-- 播放记录改为「每次播放会话一条记录」：去掉 (tenant_id,user_id,file_id) 唯一键，改普通索引。
-- 续播时取该用户该文件最新一条；播放记录列表按时间倒序展示每次播放。
ALTER TABLE video_playback_history DROP INDEX uk_tenant_user_file;
ALTER TABLE video_playback_history ADD INDEX idx_tenant_user_file (tenant_id, user_id, file_id, updated_at);
