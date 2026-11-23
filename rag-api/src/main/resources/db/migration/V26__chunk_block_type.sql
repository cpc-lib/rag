-- RAG-007：chunk 记录 PDF 版面块语义类别，供引用溯源与按类型过滤
ALTER TABLE `chunk`
    ADD COLUMN `block_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
        COMMENT 'PDF 版面块语义类别：TITLE/PARAGRAPH/LIST/TABLE/IMAGE/CODE/FORMULA' AFTER `source_id`;
