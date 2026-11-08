-- RAG-003：知识库索引别名级双版本（读别名 {base}_active + 版本化物理索引）
ALTER TABLE `knowledge_base`
    ADD COLUMN `index_version` int NOT NULL DEFAULT 0
        COMMENT '当前可读索引版本：0 表示逻辑基名（兼容升级前历史索引）' AFTER `separators`,
    ADD COLUMN `index_state` varchar(20) NOT NULL DEFAULT 'IDLE'
        COMMENT '索引状态：IDLE/BUILDING' AFTER `index_version`,
    ADD COLUMN `index_target_version` int NULL
        COMMENT '重建中的目标版本（index_state=BUILDING 时有效）' AFTER `index_state`;
