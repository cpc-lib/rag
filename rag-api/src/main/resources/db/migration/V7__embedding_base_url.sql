-- 向量化模型可配置 Base URL（OpenAI 兼容 POST /embeddings），替代固定的 DashScope 官方端点；
-- 应用层在该值为空时回退 https://dashscope.aliyuncs.com/compatible-mode/v1
ALTER TABLE model_config
    ADD COLUMN embedding_base_url VARCHAR(255) NULL AFTER embedding_model;
