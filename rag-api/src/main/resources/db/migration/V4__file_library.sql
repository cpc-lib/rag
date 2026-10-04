-- 文件库：保存字幕等操作产出的文件归档（MinIO 对象 + 元数据），同一来源字幕保存时覆盖旧版本
CREATE TABLE `library_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL COMMENT '租户编码',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `subtitle_id` bigint NULL COMMENT '来源字幕记录ID，为空表示手动上传',
  `file_name` varchar(255) NOT NULL COMMENT '文件名',
  `object_key` varchar(500) NOT NULL COMMENT 'MinIO 对象键',
  `content_type` varchar(100) NULL COMMENT '内容类型',
  `file_size` bigint NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_subtitle`(`subtitle_id` ASC) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件库' ROW_FORMAT = Dynamic;

-- 菜单：文件库（租户管理员可见，可授权给普通用户）
INSERT INTO `sys_menu` VALUES (11, 'library', '文件库', '/library', 'folder', 0, 1, 1, 85, 1, '2026-10-03 00:00:00', '2026-10-03 00:00:00');
