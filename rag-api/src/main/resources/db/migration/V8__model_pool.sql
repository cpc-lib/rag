-- 模型池改造：从"每租户一行的宽表 model_config"改为"每行一个模型"的 model 表，
-- 按 type(CHAT/VISION/EMBEDDING/IMAGE) 分类，每类最多一条 enabled=1。
CREATE TABLE model (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(16)  NOT NULL,
    base_url      VARCHAR(255),
    api_key        VARCHAR(255),
    model         VARCHAR(128),
    temperature   DECIMAL(3, 2),
    top_p         DECIMAL(3, 2),
    max_tokens    INT,
    embedding_dim INT,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_type_enabled (tenant_id, type, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 从旧宽表迁移已配置的模型（每行租户 → 最多 4 个模型行），均置为启用。
INSERT INTO model (tenant_id, name, type, base_url, api_key, model,
                   temperature, top_p, max_tokens, embedding_dim, enabled)
SELECT tenant_id, name, type, base_url, api_key, model,
       temperature, top_p, max_tokens, embedding_dim, 1
FROM (
    SELECT tenant_id,
           COALESCE(NULLIF(llm_model, ''), '对话模型') AS name,
           'CHAT' AS type,
           llm_base_url AS base_url, llm_api_key AS api_key, llm_model AS model,
           temperature, top_p, max_tokens, NULL AS embedding_dim
    FROM model_config WHERE llm_model IS NOT NULL
    UNION ALL
    SELECT tenant_id, COALESCE(NULLIF(vision_model, ''), '视觉模型'),
           'VISION', vision_base_url, vision_api_key, vision_model,
           NULL, NULL, NULL, NULL
    FROM model_config WHERE vision_model IS NOT NULL
    UNION ALL
    SELECT tenant_id, COALESCE(NULLIF(embedding_model, ''), '向量模型'),
           'EMBEDDING', embedding_base_url, embedding_api_key, embedding_model,
           NULL, NULL, NULL, embedding_dim
    FROM model_config WHERE embedding_model IS NOT NULL
    UNION ALL
    SELECT tenant_id, COALESCE(NULLIF(image_model, ''), '文生图模型'),
           'IMAGE',
           REPLACE(REPLACE(REPLACE(
               COALESCE(NULLIF(image_base_url, ''), 'https://dashscope.aliyuncs.com'),
               '/compatible-mode/v1', ''),
               '/compatible-mode', ''),
               '/api/v1', ''),
           COALESCE(NULLIF(image_api_key, ''), llm_api_key),
           COALESCE(NULLIF(image_model, ''), 'z-image-turbo'),
           NULL, NULL, NULL, NULL
    FROM model_config
    WHERE image_model IS NOT NULL OR image_api_key IS NOT NULL OR image_base_url IS NOT NULL
) AS migrated;

DROP TABLE model_config;
