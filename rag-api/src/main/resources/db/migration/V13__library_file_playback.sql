-- avi/ts 等浏览器无法解码的格式：惰性转码 mp4 后可播放，记录转码产物与状态
ALTER TABLE `library_file`
  ADD COLUMN `playback_key` varchar(500) NULL COMMENT '转码后的可播放对象键（mp4）' AFTER `object_key`,
  ADD COLUMN `playback_status` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT '转码状态：NONE/PROCESSING/READY/FAILED' AFTER `playback_key`;
