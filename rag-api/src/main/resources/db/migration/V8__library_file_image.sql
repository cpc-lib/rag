-- 文件库支持 AI 文生图业务：与 subtitle_id 并列增加 image_id 关联
ALTER TABLE `library_file`
  ADD COLUMN `image_id` bigint NULL COMMENT '来源AI图片作品ID（generated_image）' AFTER `subtitle_id`,
  ADD UNIQUE INDEX `uk_image`(`image_id` ASC) USING BTREE;

-- 历史作品一次性回填到文件库：直接复用 generated/ 下已有 MinIO 对象，不重复存储
INSERT INTO `library_file`
  (`tenant_id`, `user_id`, `subtitle_id`, `image_id`, `file_name`, `object_key`,
   `content_type`, `file_size`, `created_at`, `updated_at`)
SELECT g.`tenant_id`, g.`user_id`, NULL, g.`id`,
       CONCAT('ai-image-', g.`id`, '.png'),
       g.`object_key`, 'image/png',
       COALESCE(g.`file_size`, 0), g.`created_at`, g.`created_at`
FROM `generated_image` g
WHERE g.`status` = 'SUCCESS'
  AND NOT EXISTS (
    SELECT 1 FROM `library_file` lf WHERE lf.`image_id` = g.`id`
  );
