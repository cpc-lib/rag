-- 纠正：确保 deleted 列带 DEFAULT 0（修复 V21 在部分库上列无默认值导致的插入报错）。
ALTER TABLE `tenant` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `sys_user` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `knowledge_base` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `document` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chunk` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `pipeline_task` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `prompt_template` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `model` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `tool_config` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chat_session` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `chat_message` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `generated_image` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `subtitle` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `subtitle_cue` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `library_file` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `translate_lang` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
ALTER TABLE `video_playback_history` MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除';
