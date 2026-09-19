-- 提示词模板管理（多租户）：系统提示词在线维护，支持占位符 {{资料}} {{外部信息}} {{问题}}

CREATE TABLE `prompt_template` (
    `id`         bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`  varchar(20)  NOT NULL,
    `name`       varchar(100) NOT NULL COMMENT '模板名称',
    `content`    mediumtext   NOT NULL COMMENT '系统提示词内容，支持占位符 {{资料}} {{外部信息}} {{问题}}',
    `is_default` tinyint      NOT NULL DEFAULT 0 COMMENT '1默认模板 0普通',
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='提示词模板';
