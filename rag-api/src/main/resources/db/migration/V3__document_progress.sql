-- 文档处理百分比进度：0~100，由 worker 各流水线节点实时写入

ALTER TABLE `document`
    ADD COLUMN `progress` tinyint NOT NULL DEFAULT 0 COMMENT '处理进度百分比 0~100' AFTER `status`;

-- 存量已就绪文档回填为 100%
UPDATE `document` SET `progress` = 100 WHERE `status` = 'READY';
