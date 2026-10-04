-- 字幕详情备份表：上传解析后与 subtitle_cue 同步写入，翻译/人工编辑时同步更新。
-- 删除字幕记录时不清理本表：文件库中保留的字幕归档（SUBTITLE_SAVE）在主记录删除后，
-- 仍可依据本表还原原文/译文双列继续编辑。
CREATE TABLE `subtitle_cue_backup`  (
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
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_subtitle_seq`(`subtitle_id` ASC, `seq` ASC) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字幕详情备份表（删除字幕后供文件库归档继续编辑）' ROW_FORMAT = Dynamic;

-- 存量回填：包括已逻辑删除（deleted=1）的字幕条；备份表统一置 deleted=0，
-- 备份的生命周期独立于字幕主记录，主记录删除后仍需可查。
INSERT INTO `subtitle_cue_backup`
  (`tenant_id`, `subtitle_id`, `seq`, `start_time`, `end_time`, `content`, `translated_text`, `created_at`, `updated_at`, `deleted`)
SELECT `tenant_id`, `subtitle_id`, `seq`, `start_time`, `end_time`, `content`, `translated_text`, `created_at`, `updated_at`, 0
FROM `subtitle_cue`;

