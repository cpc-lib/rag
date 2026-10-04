-- 文件库增加视频分辨率字段：转码时由 Worker 探测写入，前端播放时展示清晰度。
ALTER TABLE library_file
    ADD COLUMN video_width  INT NULL COMMENT '视频宽度（像素，转码探测写入）',
    ADD COLUMN video_height INT NULL COMMENT '视频高度（像素，转码探测写入）';
