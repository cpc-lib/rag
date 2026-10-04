-- 文件库改为版本化归档：同一字幕多次保存保留历史版本（不再覆盖），去掉 subtitle_id 唯一键
ALTER TABLE `library_file` DROP INDEX `uk_subtitle`;
