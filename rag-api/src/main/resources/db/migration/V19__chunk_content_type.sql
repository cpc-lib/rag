ALTER TABLE `chunk`
    ADD COLUMN `content_type` varchar(16) NOT NULL DEFAULT 'TEXT'
        COMMENT 'TEXT/TABLE/IMAGE/CODE' AFTER `chunk_type`;

ALTER TABLE `knowledge_base`
    ALTER COLUMN `parent_chunk_size` SET DEFAULT 2000,
    ALTER COLUMN `child_chunk_size` SET DEFAULT 500,
    ALTER COLUMN `child_overlap` SET DEFAULT 80;
