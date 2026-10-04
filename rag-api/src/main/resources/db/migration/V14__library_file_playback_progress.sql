-- 文件库视频转码进度（0-100），供前端轮询展示
ALTER TABLE library_file ADD COLUMN playback_progress INT NULL COMMENT '转码进度 0-100';
