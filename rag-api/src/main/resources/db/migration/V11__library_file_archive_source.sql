-- 文件库归档来源：区分字幕"上传转存"与"翻译保存"两种归档，以及文件库直接上传，
-- 用于文件库删除权限控制（仅 SUBTITLE_SAVE / DIRECT 可删除）。
ALTER TABLE `library_file`
  ADD COLUMN `archive_source` varchar(32) NULL COMMENT '归档来源：SUBTITLE_UPLOAD=字幕上传转存 / SUBTITLE_SAVE=翻译保存归档 / DIRECT=文件库直接上传' AFTER `document_id`;

-- 存量字幕归档识别：文件名与字幕原始文件名一致的为上传转存（不可删）；
-- 不一致的为翻译/编辑保存产物（如 02_简体中文.srt，可删）。
-- 注：原始为 .srt 且无目标语言时保存归档与上传转存同名，此时按上传转存处理（保守不放开删除）。
UPDATE `library_file` lf JOIN `subtitle` s ON lf.`subtitle_id` = s.`id`
SET lf.`archive_source` = 'SUBTITLE_UPLOAD'
WHERE lf.`subtitle_id` IS NOT NULL AND lf.`file_name` = s.`original_name`;

UPDATE `library_file`
SET `archive_source` = 'SUBTITLE_SAVE'
WHERE `subtitle_id` IS NOT NULL AND `archive_source` IS NULL;
