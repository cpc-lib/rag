-- 文件库支持知识库文档：与 image_id 并列增加 document_id 关联
-- 注：document 表无上传者字段，历史文档归属不可靠，不做自动回填（仅新上传生效）
ALTER TABLE `library_file`
  ADD COLUMN `document_id` bigint NULL COMMENT '来源知识库文档ID（document）' AFTER `image_id`,
  ADD UNIQUE INDEX `uk_document`(`document_id` ASC) USING BTREE;
