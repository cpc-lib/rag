-- RAG-007：chunk 记录 PDF 版面元数据，支持引用溯源与页面区域定位
ALTER TABLE `chunk`
    ADD COLUMN `bbox` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
        COMMENT 'PDF 版面坐标 x,y,width,height（pt）' AFTER `section_path`,
    ADD COLUMN `source_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
        COMMENT '原文块标识，如 p3-b2 或 p3-b2~p3-b5' AFTER `bbox`;
