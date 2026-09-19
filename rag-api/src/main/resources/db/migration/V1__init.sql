-- 多租户 RAG 系统初始表结构（超级租户模式见设计文档 §13）

CREATE TABLE `tenant` (
    `id`                    varchar(20)  NOT NULL COMMENT '租户ID，000000代表超级平台租户',
    `name`                  varchar(100) NOT NULL COMMENT '租户名称',
    `status`                tinyint      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `max_storage_mb`        int          NOT NULL DEFAULT 1024 COMMENT 'MinIO 存储上限(MB)',
    `max_mq_concurrency`    int          NOT NULL DEFAULT 4 COMMENT 'MQ 任务并发额度',
    `max_llm_tokens_month`  bigint       NOT NULL DEFAULT 1000000 COMMENT 'LLM Token 月度阈值',
    `max_sse_connections`   int          NOT NULL DEFAULT 20 COMMENT '最大并发 SSE 连接数',
    `created_at`            datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户表';

CREATE TABLE `sys_user` (
    `id`            bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`     varchar(20)  NOT NULL DEFAULT '000000' COMMENT '租户ID，000000代表超级平台租户',
    `username`      varchar(50)  NOT NULL COMMENT '登录账号',
    `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 密码',
    `user_type`     tinyint      NOT NULL DEFAULT 1 COMMENT '0=平台超级管理员, 1=租户管理员, 2=租户普通用户',
    `status`        tinyint      NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
    `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='统一用户表';

CREATE TABLE `model_config` (
    `id`                 bigint        NOT NULL AUTO_INCREMENT,
    `tenant_id`          varchar(20)   NOT NULL COMMENT '租户ID',
    `llm_base_url`       varchar(255)  NULL COMMENT 'LLM 接口地址(OpenAI兼容)',
    `llm_api_key`        varchar(255)  NULL,
    `llm_model`          varchar(100)  NULL,
    `temperature`        decimal(3, 2) NOT NULL DEFAULT 0.70,
    `top_p`              decimal(3, 2) NOT NULL DEFAULT 0.90,
    `max_tokens`         int           NOT NULL DEFAULT 2048,
    `vision_base_url`    varchar(255)  NULL COMMENT '视觉大模型地址(可选)',
    `vision_api_key`     varchar(255)  NULL,
    `vision_model`       varchar(100)  NULL,
    `embedding_base_url` varchar(255)  NULL,
    `embedding_api_key`  varchar(255)  NULL,
    `embedding_model`    varchar(100)  NULL,
    `embedding_dim`      int           NOT NULL DEFAULT 1024,
    `created_at`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户模型参数';

CREATE TABLE `knowledge_base` (
    `id`                bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`         varchar(20)  NOT NULL,
    `name`              varchar(100) NOT NULL,
    `description`       varchar(500) NULL,
    `milvus_collection` varchar(64)  NULL COMMENT '绑定的 Milvus 集合 kb_{id}',
    `es_index`          varchar(64)  NULL COMMENT '绑定的 ES 索引 kb_{id}',
    `chunk_size`        int          NOT NULL DEFAULT 500 COMMENT '切片大小(字符)',
    `chunk_overlap`     int          NOT NULL DEFAULT 80 COMMENT '切片重叠(字符)',
    `separators`        varchar(255) NOT NULL DEFAULT '[\"\\n\\n\",\"\\n\",\"。\",\"？\",\"！\"]' COMMENT '分隔符 JSON 数组',
    `created_at`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库';

CREATE TABLE `document` (
    `id`         bigint       NOT NULL AUTO_INCREMENT,
    `kb_id`      bigint       NOT NULL,
    `tenant_id`  varchar(20)  NOT NULL,
    `file_name`  varchar(255) NOT NULL,
    `object_key` varchar(512) NOT NULL COMMENT 'MinIO 对象键 {tenantId}/{kbId}/{date}/{uuid}.{ext}',
    `file_size`  bigint       NOT NULL DEFAULT 0,
    `mime_type`  varchar(100) NULL,
    `status`     varchar(20)  NOT NULL DEFAULT 'PARSING' COMMENT 'PARSING/CHUNKING/EMBEDDING/INDEXING/READY/FAILED',
    `page_count` int          NOT NULL DEFAULT 0,
    `warning`    varchar(1000) NULL COMMENT '流水线降级告警(如OCR不可用)',
    `error_msg`  varchar(1000) NULL,
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb` (`kb_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文档';

CREATE TABLE `chunk` (
    `id`          bigint      NOT NULL AUTO_INCREMENT,
    `kb_id`       bigint      NOT NULL,
    `document_id` bigint      NOT NULL,
    `tenant_id`   varchar(20) NOT NULL,
    `seq`         int         NOT NULL DEFAULT 0 COMMENT '文档内切片序号',
    `content`     mediumtext  NOT NULL,
    `page`        int         NOT NULL DEFAULT 0,
    `status`      varchar(20) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO自动/MANUAL人工/DELETED已删除',
    `created_at`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_doc` (`document_id`),
    KEY `idx_kb` (`kb_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='切片';

CREATE TABLE `pipeline_task` (
    `id`          bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`   varchar(20)  NOT NULL,
    `kb_id`       bigint       NOT NULL,
    `document_id` bigint       NOT NULL,
    `type`        varchar(20)  NOT NULL COMMENT 'PARSE/REINDEX',
    `status`      varchar(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    `retry_count` int          NOT NULL DEFAULT 0,
    `payload`     text         NULL COMMENT 'MQ 消息 JSON',
    `error_msg`   varchar(1000) NULL,
    `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_doc` (`document_id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='流水线任务账本';

CREATE TABLE `tool_config` (
    `id`              bigint       NOT NULL AUTO_INCREMENT,
    `tenant_id`       varchar(20)  NOT NULL,
    `weather_enabled` tinyint      NOT NULL DEFAULT 0,
    `tavily_enabled`  tinyint      NOT NULL DEFAULT 0,
    `tavily_api_key`  varchar(255) NULL,
    `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户 Agent 工具配置';

CREATE TABLE `chat_message` (
    `id`          bigint      NOT NULL AUTO_INCREMENT,
    `tenant_id`   varchar(20) NOT NULL,
    `kb_id`       bigint      NOT NULL,
    `question`    text        NOT NULL,
    `answer`      mediumtext  NULL,
    `citations`   text        NULL COMMENT '引用来源 JSON',
    `token_usage` int         NOT NULL DEFAULT 0,
    `created_at`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_time` (`tenant_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问答留痕';

-- 超级平台租户（平台管理员账号由应用启动时初始化：admin / admin123）
INSERT INTO `tenant` (`id`, `name`, `max_storage_mb`, `max_mq_concurrency`, `max_llm_tokens_month`, `max_sse_connections`)
VALUES ('000000', '超级平台租户', 102400, 16, 10000000, 100);
