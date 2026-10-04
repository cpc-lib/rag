-- 逻辑删除改造：全部业务表加 deleted 标记列（0=正常 1=已删除）。
-- 物理删除仅作用于 MinIO/ES/Milvus 外部资源；DB 行由 MyBatis-Plus 逻辑删除自动维护。
-- 关系表（user_kb/user_prompt/user_feature/tenant_menu）、瞬态表（upload_session）、
-- 平台字典表（sys_menu/sys_tool）保持物理删除，避免重新授权唯一键冲突。

ALTER TABLE `tenant` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `sys_user` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `knowledge_base` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `document` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chunk` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `pipeline_task` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `prompt_template` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `model` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `tool_config` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chat_session` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chat_message` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `generated_image` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `subtitle` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `subtitle_cue` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `library_file` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `translate_lang` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `video_playback_history` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';

-- 唯一索引调整：逻辑删除后原行仍占位。
-- translate_lang / sys_user / library_file 降为普通索引（幂等由业务层保证）；
-- tenant.code 保留原唯一索引：业务上租户删除 = 停用（status=0），不存在 deleted=1 的租户行，无冲突场景。
ALTER TABLE `translate_lang` DROP INDEX `uk_tenant_name`, ADD INDEX `idx_tenant_name` (`tenant_id`, `name`);
ALTER TABLE `sys_user` DROP INDEX `uk_tenant_username`, ADD INDEX `idx_tenant_username` (`tenant_id`, `username`);
ALTER TABLE `library_file` DROP INDEX `uk_document`, ADD INDEX `idx_document` (`document_id`);
ALTER TABLE `library_file` DROP INDEX `uk_image`, ADD INDEX `idx_image` (`image_id`);
