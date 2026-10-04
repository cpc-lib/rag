-- 文档处理手动控制：停止标记（上传后默认 UPLOADED 待处理，点击开始才进入流水线）
ALTER TABLE `document`
  ADD COLUMN `stop_requested` tinyint NOT NULL DEFAULT 0 COMMENT '停止处理标记：0 正常，1 用户请求停止' AFTER `error_msg`;
