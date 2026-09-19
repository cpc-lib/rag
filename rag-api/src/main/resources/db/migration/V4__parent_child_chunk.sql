-- Parent-Child 切片结构 + 结构化元数据

ALTER TABLE `chunk`
    ADD COLUMN `chunk_type`     varchar(16)  NOT NULL DEFAULT 'CHILD' COMMENT 'PARENT/CHILD' AFTER `status`,
    ADD COLUMN `parent_chunk_id` bigint       NULL COMMENT '所属 parent 切片 id' AFTER `chunk_type`,
    ADD COLUMN `section_title`  varchar(512) NULL COMMENT '所属章节（叶子标题）' AFTER `parent_chunk_id`,
    ADD COLUMN `section_path`   varchar(1000) NULL COMMENT '章节完整路径，用 > 连接' AFTER `section_title`,
    ADD COLUMN `content_hash`   varchar(64)  NULL COMMENT 'SHA256(documentId+sectionPath+normalizedContent)' AFTER `section_path`;

ALTER TABLE `knowledge_base`
    ADD COLUMN `parent_chunk_size` int NOT NULL DEFAULT 1200 COMMENT 'Parent 大小（tokens）' AFTER `chunk_overlap`,
    ADD COLUMN `child_chunk_size`  int NOT NULL DEFAULT 400  COMMENT 'Child 大小（tokens）' AFTER `parent_chunk_size`,
    ADD COLUMN `child_overlap`     int NOT NULL DEFAULT 60   COMMENT 'Child 重叠（tokens）' AFTER `child_chunk_size`;

-- 存量 PARENT 尚不存在；存量切片均为 CHILD（含人工切片），无需回填
