-- 字幕详情表：上传后逐条解析入库（序号/时间轴/原文/译文分离）
-- 旧记录的 srt_content 保留作为原始快照，首次访问详情时懒迁移到本表
CREATE TABLE `subtitle_cue`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL COMMENT '租户编码',
  `subtitle_id` bigint NOT NULL COMMENT '所属字幕记录ID',
  `seq` int NOT NULL COMMENT '序号，1 起',
  `start_time` varchar(16) NOT NULL COMMENT '开始时间轴 HH:MM:SS,mmm',
  `end_time` varchar(16) NOT NULL COMMENT '结束时间轴 HH:MM:SS,mmm',
  `content` text NOT NULL COMMENT '原文文本',
  `translated_text` text NULL COMMENT '翻译后文本，NULL 表示未翻译',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_subtitle_seq`(`subtitle_id` ASC, `seq` ASC) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字幕详情表' ROW_FORMAT = Dynamic;
