-- 分片上传会话：大文件（几百 MB ~ GB）走前端分片 + MinIO 原生 Multipart，会话状态落库支持断点续传
CREATE TABLE `upload_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL COMMENT '租户编码',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `biz` varchar(32) NOT NULL DEFAULT 'LIBRARY' COMMENT '会话用途：LIBRARY=文件库直接上传',
  `file_name` varchar(255) NOT NULL COMMENT '文件名',
  `file_size` bigint NOT NULL COMMENT '文件总大小（字节）',
  `content_type` varchar(100) NULL COMMENT '内容类型',
  `chunk_size` bigint NOT NULL COMMENT '每片字节数（S3 最小 5MB，最后一片除外）',
  `total_chunks` int NOT NULL COMMENT '总分片数',
  `uploaded_chunks` int NOT NULL DEFAULT 0 COMMENT '已完成分片数',
  `uploaded_parts` text NULL COMMENT '已完成分片 JSON：[{"partNumber":1,"etag":"..."}]',
  `object_key` varchar(500) NOT NULL COMMENT 'MinIO 对象键',
  `upload_id` varchar(128) NOT NULL COMMENT 'MinIO Multipart UploadId',
  `status` varchar(16) NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING/COMPLETED/ABORTED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '分片上传会话' ROW_FORMAT = Dynamic;
