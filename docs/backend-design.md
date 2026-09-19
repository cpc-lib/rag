# 多租户 RAG 系统后端设计文档

> 依据 `spec.md` 编写。范围：`rag-api`（REST + SSE）与 `rag-worker`（异步流水线）两个独立 Maven 工程。

## 1. 总体架构

```
React 前端
   │  Axios (常规) / Fetch-SSE (流式)
   ▼
┌─────────────┐   MQ(RabbitMQ)   ┌──────────────┐
│   rag-api   │ ───────────────▶ │  rag-worker  │
│ REST + SSE  │                  │ 消费流水线任务 │
└─────────────┘                  └──────────────┘
   │  │  │  │  │                     │  │  │  │  │
   ▼  ▼  ▼  ▼  ▼                     ▼  ▼  ▼  ▼  ▼
MySQL Redis etcd MinIO          MinIO Milvus ES LLM OCR/视觉
```

- **rag-api**：鉴权（JWT）、租户/配额管理、知识库/文档/切片管理、模型参数与工具配置、SSE 流式问答、混合检索触发。
- **rag-worker**：消费 MQ 任务，执行 解析 → OCR/视觉 → 切片 → 向量化 → 双写索引（Milvus + ES），回写任务与切片状态。
- **共享契约**：两工程间仅通过 **MQ 消息 JSON Schema** 与 **MySQL 表** 解耦，不共享代码。

## 2. 中间件与版本（docker-compose 编排）

| 组件 | 版本 | 用途 |
|---|---|---|
| MySQL | 8.4 | 租户/用户/KB/文档/切片/任务/配置元数据 |
| Redis | 7 | JWT 黑名单、配额计数、SSE 并发限流、热点缓存 |
| RabbitMQ | 3.13-management | 解耦 API 与 Worker 任务 |
| MinIO | latest | 原始文件与切片物理隔离存储 |
| Milvus standalone | 2.4 | 向量存储与余弦相似度检索（自带 etcd+MinIO 依赖，复用同一 etcd） |
| Elasticsearch | 8.14 | BM25 全文检索（关闭 security 以便本地联调） |
| etcd | 3.5 | 服务注册 + 动态配置（Watch 通知热更新） |

## 3. 工程结构

```
d:\release\rag\
├── docs\backend-design.md
├── docker-compose.yml
├── rag-api\          （独立 Maven 工程，Spring Boot 3.3 / Java 17）
└── rag-worker\       （独立 Maven 工程，Spring Boot 3.3 / Java 17）
```

每工程内按层分包（轻量 DDD）：

```
com.rag.api
├── interfaces      REST 控制器、SSE 端点、全局异常/鉴权过滤器
├── application     用例服务（上传编排、问答编排、检索编排）
├── domain          实体与领域规则（配额校验、切片策略校验）
└── infrastructure  MyBatis、MinIO/Milvus/ES/Redis/etcd/LLM 客户端

com.rag.worker
├── consumer        MQ 消费者、重试/DLQ
├── pipeline        PARSE → CHUNK → EMBED → INDEX 各阶段处理器
└── infrastructure  MyBatis、MinIO/Milvus/ES/LLM/OCR/视觉 客户端
```

## 4. 数据模型（MySQL，Flyway 管理）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `tenant` | id, name, status(ENABLED/DISABLED), max_storage_mb, max_mq_concurrency, max_llm_tokens_month, max_sse_connections | 平台管理员维护；配额字段即 spec 3.1 |
| `sys_user` | id, tenant_id('000000'=超级租户), username, password_hash, user_type(0=平台超级管理员,1=租户管理员,2=租户普通用户), status | 统一用户表，见 §13；(tenant_id,username) 联合唯一 |
| `model_config` | tenant_id(UK), llm_base_url, llm_api_key, llm_model, temperature, top_p, max_tokens, embedding_base_url, embedding_api_key, embedding_model, embedding_dim | 租户管理员维护，spec 3.2 |
| `knowledge_base` | id, tenant_id, name, description, milvus_collection, es_index, chunk_size, chunk_overlap, separators(JSON) | 创建时自动生成 Milvus collection 与 ES index 名 |
| `document` | id, kb_id, tenant_id, file_name, object_key, file_size, mime_type, status(UPLOADING/PARSING/CHUNKING/EMBEDDING/INDEXING/READY/FAILED), page_count, error_msg | 状态与流水线阶段联动 |
| `chunk` | id, kb_id, document_id, tenant_id, seq, content, page, status(AUTO/MANUAL/DELETED) | 支持人工增删改（spec 3.2） |
| `pipeline_task` | id, tenant_id, kb_id, document_id, type(PARSE/REINDEX), status(PENDING/RUNNING/SUCCESS/FAILED), retry_count, payload(JSON), error_msg | RabbitMQ 消息的持久化账本 |
| `tool_config` | tenant_id(UK), weather_enabled, tavily_enabled, tavily_api_key | 工具启停（spec 3.2） |
| `chat_message` | id, tenant_id, kb_id, question, answer, citations(JSON), token_usage, created_at | 问答留痕与 Token 阈值统计 |

**租户隔离**：所有业务表带 `tenant_id`，使用 MyBatis-Plus `TenantLineInnerInterceptor` 自动拼装 `tenant_id = ?`；`user_type=0`（超级租户 000000 平台管理员）或无登录上下文（登录接口/Worker 线程）时忽略拼接实现跨租户。Milvus collection 与 ES index 均按 KB 粒度物理隔离：`kb_{kbId}`。

## 5. REST API 契约（前缀 `/api/v1`）

鉴权：`Authorization: Bearer <JWT>`（内含 userId/tenantId/userType）；`X-Tenant-Id` 仅作一致性校验头（租户用户必须与 JWT 一致，user_type=0 可省略）。

### 平台管理员
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 登录，返回 JWT 与角色 |
| POST/GET | `/tenants` | 创建/列表租户 |
| GET/PUT/DELETE | `/tenants/{id}` | 详情/更新（含配额）/停用删除，创建时返回初始租户管理员密码 |
| POST | `/tenants/{id}/admin` | 重置租户管理员账号 |

### 租户管理员
| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT | `/model-config` | LLM 参数与 Embedding 模型（spec 3.2） |
| POST/GET | `/knowledge-bases` | 创建/列表；创建即绑定 `kb_{id}` collection+index |
| GET/PUT/DELETE | `/knowledge-bases/{id}` | 详情/更新切片策略/删除（级联清索引与对象） |
| POST | `/knowledge-bases/{id}/documents` | multipart 上传 → MinIO → 建 task → 发 MQ |
| GET | `/knowledge-bases/{id}/documents` | 列表（含流水线状态轮询） |
| GET | `/documents/{id}/chunks` | 切片分页 |
| POST/PUT/DELETE | `/chunks` `/chunks/{id}` | 人工补切片/改写/删除（改动后发 REINDEX 任务） |
| GET/PUT | `/tool-config` | 天气/Tavily 启停与密钥 |
| GET | `/quotas/usage` | 存储/MQ 并发/Token/SSE 连接用量 |

### 问答（SSE）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/stream` | `Accept: text/event-stream`，body: `{kbId, question}`；事件契约见 §8 |

## 6. RabbitMQ 拓扑

- Exchange：`rag.ingest`（direct）
- Queue：`rag.ingest.parse`（主）、`rag.ingest.dlq`（死信）
- 路由键：`parse` / `reindex`
- 消息体：`{taskId, type, tenantId, kbId, documentId, objectKey}`
- 可靠性：publisher confirm + 手动 ack；失败 retry_count<3 时重回队列（延迟重试），超限入 DLQ 并置 task FAILED
- Worker 并发：`concurrency` 受租户 `max_mq_concurrency` 配额约束（Redis 信号量计数）

## 7. Worker 流水线（状态机）

```
PENDING → PARSING → CHUNKING → EMBEDDING → INDEXING → SUCCESS
              └──────失败──────────┴──────────┘→ retry(≤3) → DLQ/FAILED
```

1. **PARSE**：按 MIME 分发——MD/TXT 直接读；DOCX/XLSX 用 POI（Excel 行列转结构化文本）；PDF 用 PDFBox 提取文本层，无文本层判定扫描件走 OCR（Tess4J，按页）；图片先走视觉大模型生成图文描述（未配视觉模型则仅 OCR）。
2. **CHUNK**：按 KB 策略（chunk_size/overlap/分隔符）滑动切片，写 `chunk` 表（status=AUTO）。
3. **EMBED**：调用租户配置的 Embedding 接口（OpenAI 兼容 `/embeddings`），批量 ≤10 条分批。
4. **INDEX**：Milvus `kb_{kbId}` 集合 insert（chunk_id + 向量，Cosine 度量）；ES `kb_{kbId}` 索引 index（content + fileName/page 等元数据，ik 分词器，缺失时回退 standard）。
5. 回写 `document.status=READY`、`pipeline_task.status=SUCCESS`，推送etcd 配置版本号供 API 感知。

## 8. SSE 事件契约（与 spec 5.1 一致）

```
event: search_start    data: {"stage":"kb"|"tool","tool":"weather|tavily"}
event: search_result   data: {"citations":[{"seq":1,"chunkId":"..","documentId":"..","documentName":"..","page":3,"previewUrl":"..(MinIO 预签名,15min)"}]}
event: message         data: {"delta":"增量文本"}
event: error           data: {"code":"QUOTA_EXCEEDED","message":".."}
event: done            data: {"usage":{"promptTokens":..,"completionTokens":..}}
```

链路：建连（Redis 按 `sse:conn:{tenantId}` INCR 限流，超 `max_sse_connections` 返回 429）→ search_start → 混合检索 → search_result → 组装 RAG Prompt → LLM stream 逐块转发 message → done；`SseEmitter` 超时 60s；中断不自动重试，保留半成品由前端手动“重新生成”。

## 9. 混合检索与 Agent 工具

- **双路召回**：Milvus top20（余弦）+ ES top20（BM25）→ **RRF（k=60）** 融合取 Top-8。
- **工具路由**（检索前意图识别，由 LLM function-calling 实现，受 `tool_config` 开关约束）：
  - `query_weather`：气象意图+地域实体 → 外部 Weather API → 结果注入上下文；
  - `tavily_search`：知识库召回最高 RRF 得分低于阈值 或 用户明确要求最新资讯 → Tavily API。
- 工具调用过程以 `search_start`/`search_result` 事件流式透出。

## 10. etcd 用途

- **服务注册**：`/rag/registry/{service}/{instance}`（租约 15s keepalive），API/Worker 启动注册、优雅下线注销。
- **动态配置**：`/rag/config/tenant/{tenantId}/tools`、`/rag/config/global/retrieval`（topK、RRF k、召回阈值等）；Watch 前缀变更 → 热刷新内存缓存，无需重启。

## 11. 关键非功能项

| 项 | 策略 |
|---|---|
| 鉴权 | JWT HS256（2h），Redis 黑名单登出；RBAC 按 `user_type`：0=平台超级管理员（超级租户 000000，跨租户特权）、1=租户管理员、2=租户普通用户 |
| 配额 | 上传前查 `max_storage_mb`（SUM(file_size)），SSE 建连查 Redis 计数，Token 月阈值超限触发 `error: TOKEN_QUOTA_EXCEEDED` |
| 容错 | LLM/Embedding 超时 30s、重试 2 次；OCR 失败降级为仅文本解析并标记 document.warning |
| 日志 | 任务级结构化日志（taskId 贯穿）；审计：登录/删除/配额变更 |

## 12. 构建与运行

```bash
docker compose up -d          # 拉起全部中间件
cd rag-api    && mvn spring-boot:run     # :8080
cd rag-worker && mvn spring-boot:run     # 无 Web 端口，纯消费者
```

Flyway 首次启动自动建表；冒烟路径：登录 → 建 KB → 上传文档 → 轮询 READY → `POST /chat/stream` 验证五类事件。

## 13.超级租户模式 (Super Tenant)
在这种模式下，平台管理员本质上是挂载在系统内置的“超级租户”下的特殊用户。

1. 核心数据库表设计
在统一的 sys_user 表中，引入 tenant_id 和 user_type 两个核心字段。

SQL
CREATE TABLE `sys_user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL DEFAULT '' COMMENT '租户ID，000000代表超级平台租户',
  `username` varchar(50) NOT NULL COMMENT '登录账号',
  `password` varchar(100) NOT NULL COMMENT '密码',
  `user_type` tinyint(2) DEFAULT '1' COMMENT '用户类型：0=平台超级管理员, 1=租户管理员, 2=租户普通用户',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态：1正常, 0停用',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_username` (`tenant_id`,`username`) -- 多租户下的联合唯一索引
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一用户表';
2. 持久层 (MyBatis-Plus) 租户拦截器配置
多租户 SaaS 系统的核心在于防止租户 A 查到 租户 B 的数据。使用 MyBatis-Plus 的 TenantLineInnerInterceptor 时，需要为“超级租户”配置特权：

普通租户登录时：拦截器自动在所有 SQL 末尾追加 WHERE tenant_id = '当前租户ID'。

平台管理员登录时：从 ThreadLocal 或 Redis 中识别到当前请求的 user_type = 0 或 tenant_id = 0，拦截器主动放行（忽略租户拼接），从而允许平台管理员跨租户查询系统的全局统计数据（如监控所有租户的 MinIO 存储量、Token 消耗量）。

3. 前端动态路由设计 (React + Zustand)
前端保持单一入口，登录成功后，根据返回的 user_type 动态加载不同的控制台：

如果 user_type === 0：Zustand 状态机加载【平台级菜单】（租户列表、系统配置、全局 MQ 监控、全局计费管理）。

如果 user_type === 1：Zustand 状态机加载【租户级菜单】（知识库管理、文档切片、模型参数、问答日志）。
