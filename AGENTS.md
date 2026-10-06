# AGENTS.md

本文件供 AI 编程助手（及新加入的开发者）快速建立对本仓库的正确认知。动手改代码前请通读本文件。

## 1. 项目是什么

多租户 RAG 知识库问答平台：租户隔离的知识库管理 + 文档异步解析流水线（解析→切片→向量化→双索引）+ 混合检索（向量/BM25/RRF/可选 Rerank）+ LLM SSE 流式问答 + AI 文生图 + 外部工具（天气/Tavily）。

四个模块（各模块独立 pom.xml，均以 `spring-boot-starter-parent` 为 parent，**模块间无父子继承**；根目录有纯聚合 `pom.xml` 仅用于一键构建）：

| 模块 | 形态 | 端口 | 职责 |
|---|---|---|---|
| `rag-common` | 公共 jar（无服务） | 无 | 25 张表实体 + Mapper 接口 + MyBatis XML（`resources/mapper/`），被 api/worker 共享 |
| `rag-api` | Spring Boot 3.3.5 Web（Java 17） | 8080 | REST API、SSE 问答、鉴权、模型/租户/配额管理 |
| `rag-worker` | Spring Boot 3.3.5 **非 Web**（Java 17） | 无 | RabbitMQ 消费者，文档解析/切片/Embedding/ES+Milvus 双写 |
| `knowledge-console` | React 18 + TS + Vite 5 + Antd 5 | 5173 | 管理控制台，`/api` 代理到 8080 |

## 2. 本地启动

### 2.1 中间件（根目录 docker-compose.yml）

```bash
docker compose up -d mysql redis minio mc-init etcd elasticsearch milvus
```

MySQL 8.4 / Redis 7.4 / MinIO（9000 API + 9001 控制台）/ etcd 3.5 / ES 8.14 / Milvus 2.5 standalone。MinIO 桶由 `mc-init` 自动建：`rag-files`（业务）、`a-bucket`（Milvus）。

> 注意：compose 不包含 `rabbitmq`，但 rag-worker 代码依赖 RabbitMQ（exchange `rag.ingest`）。本地跑 Worker 前需自备 RabbitMQ（5672/15672，guest/guest）。

所有中间件地址默认指向 `192.168.1.200`，可用环境变量覆盖：`MIDDLEWARE_HOST`、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`MINIO_PORT`、`ES_PORT`、`MILVUS_PORT`、`ETCD_PORT` 等。

### 2.2 数据库初始化（Flyway 自动迁移）

使用 **Flyway** 管理数据库版本，应用启动时自动执行迁移脚本（位于 `rag-api/src/main/resources/db/migration/`）：

- `V1__init_schema.sql` — 全量建表 + 种子数据（合并自历史 V1–V20）
- 后续表结构变更新增 `V2__xxx.sql`、`V3__xxx.sql` …，**不要修改已发布的迁移脚本**

配置（`rag-api/src/main/resources/application.yml`）：

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true   # 已有库自动基线到 V1，不重跑初始化
    baseline-version: 1
    clean-disabled: true
```

> **已有数据库迁移**：若库中残留旧的 `flyway_schema_history` 表（来自项目早期版本），需先 `DROP TABLE flyway_schema_history;` 再启动，避免校验失败。空库直接启动即可自动建表。

rag-worker **不引入 Flyway**（两进程连同一库，只由 rag-api 负责迁移）。

### 2.3 启动应用

```bash
# 后端两个进程必须使用同一套密钥/中间件（API 与 Worker 共享 MySQL/MinIO/Milvus/ES）
mvn -f rag-api/pom.xml spring-boot:run      # http://localhost:8080
mvn -f rag-worker/pom.xml spring-boot:run   # 无 Web 端口，纯消费者

cd knowledge-console
npm install
npm run dev                                  # http://localhost:5173
```

平台管理员种子账号（首次启动由 `DataInitializer` 创建）：`admin / admin123`，挂载在超级租户 `000000` 下（userType=0）。

### 2.4 构建与测试

```bash
mvn clean install -DskipTests               # 根目录聚合一键构建（reactor 自动按依赖排序：rag-common → rag-api/rag-worker）
mvn -f rag-api/pom.xml clean compile -DskipTests   # 单模块构建前先 install rag-common
mvn -f rag-worker/pom.xml clean test         # 唯一测试：ChunkPipelineTest
cd knowledge-console && npm run typecheck    # tsc --noEmit
cd knowledge-console && npm run build
```

## 3. 架构地图（改代码先定位）

### 3.1 rag-api 包结构 `com.rag.api`

- `interfaces/` — Controller（路由前缀统一 `/api/v1`）、`dto/Dtos.java`（record 集中定义）、`GlobalExceptionHandler`、`security/{JwtAuthFilter,AuthGuard}`、`guard/SseConnectionGuard`
- `application/` — 业务服务（无接口的单实现 @Service）：`AuthService`、`ChatOrchestrator`（SSE 编排）、`RetrievalService`（混合检索）、`DocumentAppService`、`ChunkAppService`、`KnowledgeBaseService`、`PromptTemplateService`、`ModelService`、`TenantService`、`UserManageService`、`MenuService`、`ToolConfigService`、`QuotaService`、`ChatSessionService`、`ImageGenerationService`
- `infrastructure/`（**persistence 层已整体抽到 rag-common**，本模块不再有 entity/mapper）
  - `llm/LlmClient`、`image/ZImageClient`（文生图）
  - `search/{EsSearchClient,MilvusClientWrapper}`、`storage/MinioStorage`、`etcd/EtcdService`、`mq/IngestPublisher`
  - `tool/{WeatherClient,TavilyClient}`
- `common/` — `ApiResult`、`BizException`、`ErrorCode`、`TenantContext`（ThreadLocal 会话）
- `config/` — `DataInitializer`（种子账号）、`MinioConfig`、`MybatisPlusConfig`、`RabbitMqConfig`、`WebCorsConfig`

### 3.2 rag-common 包结构 `com.rag.api.infrastructure.persistence`

- `entity/` — 25 张表实体（`@TableName` + Lombok `@Data`，**无 @TableLogic**，逻辑删除由 XML 手写）
- `mapper/` — 25 个 Mapper **普通接口**（不 extends BaseMapper），方法由 XML 实现
- `resources/mapper/*.xml` — 所有 DB CRUD 的唯一实现：SELECT 显式 `deleted = 0`（有该列的表）、删除统一 `UPDATE SET deleted = 1`、insert 用 `useGeneratedKeys`
- `config/RagPersistenceAutoConfiguration` — MetaObjectHandler 自动填充：insert 补 `createdAt/updatedAt`、update 刷新 `updatedAt`；**必须判空 setValue，禁用 strictInsertFill**（普通接口 Mapper 不注册 TableInfo，strict 填充会静默跳过）；经 `AutoConfiguration.imports` 注册，api/worker 两端生效
- 两端 yml 均已配置 `mybatis-plus.mapper-locations: classpath*:mapper/*.xml`；worker 的 `@MapperScan` 指向 `com.rag.api.infrastructure.persistence.mapper`

### 3.3 rag-worker 包结构 `com.rag.worker`

- `consumer/IngestConsumer` — 手动 ack、按租户信号量限流（`TenantMqSemaphore`）、失败延迟重试（TTL+DLQ，`RetryPublisher`），超 `rag.worker.max-retry` 置 FAILED
- `pipeline/PipelineProcessor` — 状态机：`PARSE`（解析→切片→Embedding→双写）与 `REINDEX`（保留切片重嵌重建）
- `pipeline/ParseService` — txt/md 直读；docx/xlsx 用 POI；pdf 用 PDFBox 2.x 文本层 + 扫描页 Tess4J OCR；html 用 jsoup；图片走 Vision
- `pipeline/chunk/` — 15 种切片策略（见 §5），SPI 风格：`ChunkStrategy.mode()/plan(ChunkContext)`；`ChunkStrategyRouter` 注入 `List<ChunkStrategy>` 并解析 AUTO；`ChunkSupport` 提供共享机制
- `infrastructure/llm/{EmbeddingClient,VisionClient}`、`ocr/OcrService`、`search/{EsIndexer,MilvusIndexer}`、`storage/MinioStorage`、`etcd/WorkerRegistry`
- **PDFBox 是 2.x**：用 `PDDocument.load(...)`，不存在 `org.apache.pdfbox.Loader`（那是 3.x API）

### 3.3 knowledge-console/src

- `pages/` — 每页一个功能：Login/Tenants（平台）/ModelConfig/KnowledgeBases(+KbDetail)/Prompt/ToolConfig/Quota/Users/Chat/ImageStudio
- `api/` — axios 封装（`client.ts` 拦截器注入 Token/Tenant 头）+ `types.ts` 集中类型
- `store/auth.ts`（Zustand）、`components/MainLayout.tsx`、`App.tsx`（路由 + `RequireAuth/RequireUserType/RequireMenu`）
- SSE 问答用 `@microsoft/fetch-event-source`，不用 axios

## 4. 核心链路

### 4.1 文档上传到可检索

```
POST /api/v1/knowledge-bases/{kbId}/documents (multipart)
  DocumentAppService.upload
    ├─ QuotaService.checkStorage（存储配额）
    ├─ MinIO 上传原文件  key: {tenantId}/{kbId}/{yyyy-MM-dd}/{uuid}.{ext}
    └─ document 落库 UPLOADED（待处理）+ 文件库登记；**不自动投递**，等待手动触发
POST /api/v1/documents/{id}/start（开始/继续处理，DocumentAppService.startProcessing）
    ├─ 已有切片 → resume=true：document 置 EMBEDDING，Worker 跳过解析/切片断点续跑
    ├─ 无切片 → resume=false：document 置 PARSING，从头跑
    └─ pipeline_task(PARSE, PENDING) + IngestPublisher → exchange rag.ingest
POST /api/v1/documents/{id}/stop（停止，DocumentAppService.stopProcessing）
    └─ stop_requested=1；任务仍 PENDING 则直接 CANCELLED + 文档 STOPPED，
       否则 Worker 在阶段边界/向量化批次间检测（StoppedException，不重试）→ STOPPED
         ↓
IngestConsumer（租户并发信号量 → RUNNING）
  PipelineProcessor.parseFlow
    PARSING   ParseService.parse（产物写 MinIO derived/{version}/）
    CHUNKING  ChunkStrategyRouter.route(kb.chunk_strategy, 文件名).plan(ctx) → persistPlans
    EMBEDDING EmbeddingClient.embed（OpenAI 兼容 /embeddings，每批 ≤10 条）
    INDEXING  Milvus（先删后写，schema 仅 [chunk_id, document_id]）
              + ES（索引名 kb.es_index，BM25）
    READY 100
```

### 4.2 问答检索（RetrievalService）

```
Milvus 向量召回 vectorTopN(20) + ES BM25 keywordTopN(20)
  → RRF 融合(rrf-k=60) 取 topK(8)
  → 可选 RERANK 模型二阶段精排（未配置/调用失败静默降级，沿用 RRF 序）
  → CHILD 命中扩展到 PARENT 内容并按 parent 去重
  → Token Budget（max-context-tokens=12000）截断
  → 组装 system prompt → LLM SSE
```

SSE 事件类型（ChatOrchestrator）：`session` / `search_start` / `search_result`（含 citations）/ `message`（delta）/ `done`（usage）/ `error`。

### 4.3 切片的 Parent-Child 模型

`chunk` 表 `chunk_type` 为 PARENT/CHILD；只有 CHILD 参与 embedding 与索引，检索命中后在 DB 内扩展到 parent。人工新增/编辑的切片 `status=MANUAL`，重解析只删 `AUTO` 行；手工编辑 AUTO child 会使其脱离 parent。

## 5. 切片策略（15 种，ChunkMode 枚举）

`FIXED_SIZE / RECURSIVE / PARAGRAPH / SENTENCE / SEMANTIC / STRUCTURE / MARKDOWN / HTML / PDF_LAYOUT / TABLE / QA / PARENT_CHILD / SLIDING_WINDOW / CODE / AUTO`

- AUTO 映射（当前代码）：`pdf→PDF_LAYOUT`；`java/py/go/js/jsx/ts/tsx→CODE`；其余→`PARENT_CHILD`
- 知识库 `chunk_strategy` 字段显式指定，AUTO 为默认
- 详细切片/多模态/语义边界规则见根文档 [RAG_DOCUMENT_PROCESSING_PIPELINE.md](RAG_DOCUMENT_PROCESSING_PIPELINE.md)（§1–§32），改 chunk 相关代码前必读
- 估算 token 用 worker 的 `TokenCounter`（中文 1 字≈1 token，ASCII run≈len/4），**不要引入重量级 tokenizer**

## 6. 数据模型（23 张业务表）

`tenant / sys_user / sys_menu / sys_tool / tenant_menu / user_feature / user_kb / user_prompt / knowledge_base / document / chunk / pipeline_task / prompt_template / model / tool_config / chat_session / chat_message / generated_image / subtitle / subtitle_cue / library_file / translate_lang`

关键事实：
- 用户角色 `user_type`：**0 平台管理员 / 1 租户管理员 / 2 普通用户**；租户编码 `tenant_code` 唯一且创建后不可改
- 模型池 `model`：类型 `CHAT/VISION/EMBEDDING/IMAGE/RERANK`，每类型每租户最多一条 `enabled=1`
- 权限两层：菜单（`user_feature` MENU，如 chat/image-studio）+ 工具（TOOL）；知识库/提示词另有 `user_kb/user_prompt` 数据授权
- `chunk` 实体**没有** tokenCount 字段（DDL 无此列）

## 7. 配置与外部依赖

- 后端配置命名空间 `rag.*`：jwt、minio、milvus、etcd、mq、retrieval（top-k/rrf-k/max-context-tokens 等）、llm 超时、chat SSE 超时、worker 重试、ocr tessdata
- etcd 双重身份：Milvus 元数据后端 + 业务动态配置/注册中心，业务前缀 `/rag/`；检索参数 etcd `/rag/config/global/retrieval` 可热更覆盖 yml 兜底值
- 模型连接：OpenAI 兼容 HTTP（WebClient），Base URL 入库时自动去除首尾反引号/引号/空白/斜杠；Embedding 每批 ≤10（阿里云 MAAS 限制）
- LLM/WebClient 用 `ConnectionProvider.newConnection()`，避免空闲长连接被中间网络设备 RST
- 出站管控：第三方模型 endpoint 需租户出站确认（本地模型不可保存第三方地址）

## 8. 编码约定（必须遵守）

1. **模块结构**：根目录有纯聚合 `pom.xml`（packaging=pom，仅含 modules），用于 `mvn clean install` 一键构建；rag-api/rag-worker/rag-common 各自独立 pom，均以 `spring-boot-starter-parent` 为 parent，**模块间无父子继承**。rag-common 先 install，api/worker 通过 `<dependency>` 引用。各模块 pom 中显式声明 UTF-8 与 `<maven.compiler.parameters>true</maven.compiler.parameters>`（RabbitMQ 等 bean 参数名消歧）
2. Controller 只做参数与鉴权（`AuthGuard.requirePlatform()/requireTenantAdmin()` 静态调用），业务在 `application/`；DTO 一律加到 `interfaces/dto/Dtos.java` 的 record 中
3. 跨租户访问一律返回"不存在"（NOT_FOUND），不泄露资源存在性；未匹配路由由 `GlobalExceptionHandler` 返回 404 `请求的资源不存在`（含 Spring 6 `NoResourceFoundException` 专项处理）
4. 租户上下文取自 `TenantContext`（JWT 过滤器填充），SQL 必须带 `tenant_id` 条件，不允许跨租户 IN 批查遗漏隔离
5. 外部调用失败的降级策略要显式：Rerank/OCR/Vision 失败静默降级并打 warn 日志，不阻断主流程；存储配额统计优先 MinIO 真实用量，不可达才回退 DB 求和
6. 中文注释、中文用户提示信息；JSON 响应中文断言用 UTF-8（MockMvc 默认 ISO-8859-1）
7. 前端：类型集中在 `api/types.ts`；UI 用 Antd 5，风格简洁明亮；按钮字体清晰；权限相关弹窗用列表勾选（菜单授权与工具授权分两个弹窗），不要做非要求的"灵活配置"
8. 最小改动原则：不顺手重构无关代码、不重排格式；改动产生的孤儿 import/方法要清理，历史死代码只提示不删除
9. 不新建文档文件，除非用户明确要求；文档变更与代码变更在同一提交内说明

## 9. 常见坑位

- **启动报连不上 192.168.1.200**：中间件没起或地址不对，用 §2 的环境变量指向本机；HikariPool 反复 Starting 就是 MySQL 不可达
- **Vite 代理 ECONNREFUSED**：rag-api(8080) 没启动，不是前端问题
- **改了 @Service 构造函数后**：跑一次 `mvn clean test`，增量编译可能残留 "Unresolved compilation problem" class
- **PDFBox API 报错**：确认用的是 2.x 的 `Loader` 不存在；`PDDocument.load`
- **MinIO 预签名 URL 403**：签名 URL 含 `?Expires&Signature`，WebClient 必须 `.uri(URI.create(url))`，用字符串会被当模板二次编码
- **文生图 404 NOT_FOUND**：image base 要填裸 host，不能带 `/compatible-mode/v1` 后缀（`ZImageClient` 会规整一次但别依赖）
- **MyBatis XML/实体包名**：所有实体与 Mapper 已统一抽到 rag-common（`com.rag.api.infrastructure.persistence.*`），worker 直接引用 rag-common 的类，**不再有 `com.rag.worker.*` 的持久层类**。新增表时实体/Mapper/XML 都加到 rag-common
- **Windows PowerShell**：不支持 `&&` 连接命令，用 `;` 或分行；路径含中文注意 UTF-8

## 10. 参考文档

- [RAG_DOCUMENT_PROCESSING_PIPELINE.md](RAG_DOCUMENT_PROCESSING_PIPELINE.md) — 文档处理流水线完整规格（解析/切片/多模态/检索/Rerank/预算，§1–§32）
- [CODE_INTRO.md](CODE_INTRO.md) — 代码逐包导读与关键流程详解
