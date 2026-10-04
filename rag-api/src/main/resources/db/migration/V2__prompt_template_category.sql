-- 扩展 prompt_template 支持非知识库场景的提示词（如字幕翻译）
-- kb_id 改为可空：NULL 表示不属于任何知识库的全局模板
-- 新增 category 列：NULL=知识库问答（兼容历史数据），SUBTITLE=字幕翻译
ALTER TABLE `prompt_template`
    MODIFY COLUMN `kb_id` bigint NULL COMMENT '所属知识库ID，NULL表示非知识库模板';

ALTER TABLE `prompt_template`
    ADD COLUMN `category` varchar(32) NULL COMMENT '模板分类：NULL/KB_QA=知识库问答，SUBTITLE=字幕翻译' AFTER `kb_id`;

ALTER TABLE `prompt_template`
    ADD INDEX `idx_tenant_category` (`tenant_id` ASC, `category` ASC);
