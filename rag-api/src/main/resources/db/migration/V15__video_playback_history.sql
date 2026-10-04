-- 视频播放进度记录：按租户+用户+文件维度保存最新播放位置，用于"续播"与"播放记录"。
CREATE TABLE IF NOT EXISTS video_playback_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL COMMENT '租户ID',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    file_id     BIGINT       NOT NULL COMMENT 'library_file.id',
    file_name   VARCHAR(512) NOT NULL COMMENT '文件名快照（删除文件后仍可展示记录）',
    position_ms BIGINT       NOT NULL DEFAULT 0 COMMENT '最新播放位置（毫秒）',
    duration_ms BIGINT       NOT NULL DEFAULT 0 COMMENT '视频总时长（毫秒）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_user_file (tenant_id, user_id, file_id),
    KEY idx_user_updated (tenant_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频播放进度记录';
