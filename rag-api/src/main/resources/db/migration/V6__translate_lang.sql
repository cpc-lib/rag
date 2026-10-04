-- 字幕翻译目标语言（租户级维护）：语言名直接作为翻译提示词 {{目标语言}} 传参
CREATE TABLE `translate_lang`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL COMMENT '租户编码',
  `name` varchar(50) NOT NULL COMMENT '语言显示名，如 简体中文/English/日本語',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_name`(`tenant_id` ASC, `name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字幕翻译目标语言' ROW_FORMAT = Dynamic;
