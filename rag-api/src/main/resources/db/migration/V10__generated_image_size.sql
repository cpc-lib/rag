-- 作品列表展示文件大小。
ALTER TABLE generated_image ADD COLUMN file_size BIGINT AFTER file_name;
