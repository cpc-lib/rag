-- 文生图能力（阿里云 Z-Image，指南外新增能力）：
-- 1. 模型配置增加图像生成参数（留空时回退 DashScope 官方地址 / LLM 的 API Key / z-image-turbo）
ALTER TABLE `model_config`
    ADD COLUMN `image_base_url` varchar(255) NULL COMMENT '文生图服务地址，空=https://dashscope.aliyuncs.com'
        AFTER `embedding_dim`,
    ADD COLUMN `image_api_key` varchar(255) NULL COMMENT '文生图 API Key，空则回退 llm_api_key'
        AFTER `image_base_url`,
    ADD COLUMN `image_model` varchar(64) NULL COMMENT '文生图模型，空=z-image-turbo'
        AFTER `image_api_key`;

-- 2. 生成图片历史（图片持久化到 MinIO，DashScope 链接仅 24h）
CREATE TABLE `generated_image` (
    `id`         bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`  varchar(32)  NOT NULL,
    `user_id`    bigint       NOT NULL,
    `prompt`     text         NOT NULL,
    `model`      varchar(64)  NOT NULL,
    `size`       varchar(32)  NULL,
    `seed`       bigint       NULL,
    `object_key` varchar(500) NOT NULL,
    `file_name`  varchar(255) NOT NULL,
    `status`     varchar(16)  NOT NULL DEFAULT 'SUCCESS',
    `error_msg`  varchar(1000) NULL,
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_user` (`tenant_id`, `user_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文生图生成历史';
