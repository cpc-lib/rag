# CODE_INTRO.md — 代码导读

面向需要阅读/修改本仓库代码的工程师。配合 [AGENTS.md](AGENTS.md)（构建运行与约定）和 [RAG_DOCUMENT_PROCESSING_PIPELINE.md](RAG_DOCUMENT_PROCESSING_PIPELINE.md)（切片与检索规格）使用。

---

## 1. 仓库总览

```
rag/
├── rag-api/             Spring Boot Web：API + SSE 问答（:8080）
├── rag-worker/          Spring Boot 非 Web：MQ 消费者 + 文档流水线
├── knowledge-console/   React18 + TS + Vite 控制台（:5173）
├── docker-compose.yml   本机中间件（MySQL/Redis/MinIO/etcd/ES/Milvus）
├── rag_demo.sql 等价物： rag-api/src/main/resources/db/migration/rag_demo.sql（手动建表，无 Flyway）
├── RAG_DOCUMENT_PROCESSING_PIPELINE.md   文档流水线规格（§1–§32）
├── AGENTS.md            AI/新人上手指南
└── CODE_INTRO.md        本文件
```

技术栈：Java 17 + Spring Boot 3.3.5 + MyBatis-Plus + MySQL 8 + Redis + RabbitMQ + MinIO + Milvus 2.5 + Elasticsearch 8 + etcd 3.5；前端 React 18 / Antd 5 / Zustand / Vite 5。

两个后端进程共享同一个 MySQL、MinIO、Milvus、ES、etcd；**各自有一套独立的实体/Mapper 类**（包名不同，禁止互相依赖），API 侧偏交易与查询，Worker 侧偏流水线。

---

## 2. rag-api 逐包导读

根包 `com.rag.api`，启动类 `RagApiApplication`。

### 2.1 interfaces —— Web 入口层

| 类 | 路由 / 职责 |
|---|---|
| `AuthController` | `/api/v1/auth`：login / logout / me / change-password |
| `TenantController` | `/api/v1/tenants`：平台管理员（userType=0）管理租户，含 `/{id}/admin` 建租户管理员 |
| `KnowledgeBaseController` | `/api/v1/knowledge-bases`：KB CRUD |
| `DocumentController` | KB 下文档上传(multipart)/列表/删除/reparse/下载预签名 |
| `ChunkController` | `/api/v1/.../chunks`：切片列表/详情/新增/编辑/删除（详情弹窗编辑用） |
| `PromptTemplateController` | `/api/v1/prompts`：提示词模板（必须归属某 KB，每 KB 一个默认模板） |
| `ModelController` | `/api/v1/models`：模型池 CRUD + enable/disable（每类型单启用） |
| `UserManageController` | `/api/v1/users`：用户、重置密码、KB 数据授权 |
| `MenuController` | `/api/v1/menus` + `/grantable`：菜单目录与租户菜单开通 |
| `ToolController` / `ToolConfigController` | 工具目录 / 租户工具开关与密钥配置 |
| `ChatController` | `POST /api/v1/chat/stream`（text/event-stream）+ 会话相关 |
| `ChatSessionController` | `/api/v1/chat-sessions`：历史会话（按 tenant+user 隔离） |
| `ImageController` | `/api/v1/images`：generate / 历史列表 / `/{id}/download`（鉴权后代理下载） |
| `QuotaController` | `GET /api/v1/quotas/usage`：存储/Token/SSE/MQ 配额 |
| `GlobalExceptionHandler` | 统一异常 → `ApiResult`；含 Spring 6 `NoResourceFoundException` → 404 |
| `dto/Dtos.java` | **全部**请求/响应 record 集中处 |
| `security/JwtAuthFilter` | 见 §2.4 |
| `security/AuthGuard` | 静态鉴权：`requirePlatform()` / `requireTenantAdmin()` |
| `guard/SseConnectionGuard` | 租户级 SSE 并发连接计数（配额页展示） |

### 2.2 application —— 业务服务层

- **`AuthService`**：登录校验、签发 JWT（委托 JwtAuthFilter.createToken）、登出加 Redis 黑名单、改密
- **`ChatOrchestrator`**：问答主编排。意图判定 → 需要知识库则走 `RetrievalService` → 需要工具（天气/Tavily）则工具调用循环 → LLM 流式输出。SSE 事件：`session`、`search_start`、`search_result`（citations 含 rerankScore）、`message`（delta）、`done`（token usage）、`error`
- **`RetrievalService`**：混合检索，见 §4.2
- **`DocumentAppService`**：上传（配额校验→MinIO→落库→投 MQ）、列表、预签名下载、删除（事务内删 chunk/task + 同步清 ES/Milvus/MinIO）、reparse（投 REINDEX）
- **`ChunkAppService`**：切片查询/手工增改删。手工编辑 AUTO child 会置空 parent_chunk_id；删除 child 会解散其 parent
- **`KnowledgeBaseService`**：KB CRUD + `getOwned()`（租户归属校验，跨租户抛 NOT_FOUND）；建 KB 时自动种默认提示词模板；切片参数（parent/child/overlap/separators/strategy）
- **`PromptTemplateService`**：模板归属 KB；普通用户仅见被授权模板；删除 KB 级联清模板与授权
- **`ModelService`**：模型池。常量 `CHAT/VISION/EMBEDDING/IMAGE/RERANK`；`findEnabled(tenant,type)` 无缓存，改密钥即时生效；enable 同类互斥顶替
- **`TenantService`**：租户管理与配额字段（maxStorageMb/maxLlmTokensMonth/maxSseConnections/maxMqConcurrency）
- **`UserManageService`**：用户与授权。菜单授权与工具授权走 `user_feature`（MENU/TOOL）两个独立列表勾选；KB/模板授权走 user_kb/user_prompt；列表固定 3 条 IN 批查避免 N+1
- **`MenuService` / `ToolConfigService`**：菜单目录、租户可用菜单；工具租户开关 + 密钥（工具可用 = 租户开关 AND 用户授权）
- **`QuotaService`**：存储用量**优先 MinIO 按租户前缀实际统计**（`statPrefixSize`，覆盖原文件/生成图/derived 产物），MinIO 不可达降级 DB 求和（document+generated_image）；`checkStorage` 上传拦截；Token 月配额；SSE 并发
- **`ChatSessionService`**：会话与消息持久化、token_usage 累计
- **`ImageGenerationService`**：文生图（z-image 同步接口）→ 下载 → MinIO（`{tenant}/generated/{user}/uuid.png`）→ generated_image 落库；上传前同样 checkStorage

### 2.3 infrastructure —— 基础设施

- `persistence/entity`（19 个 @TableName 实体）+ `persistence/mapper`（BaseMapper）
- `llm/LlmClient`：OpenAI 兼容 chat completions，WebClient + SSE；`newConnection()` 防连接 RST；SSE payload 直接处理（不检查 `data:` 前缀）
- `image/ZImageClient`：文生图同步 POST + 图片下载；Base URL 规整（剥离 `/compatible-mode/v1` 等后缀）；下载签名 URL 必须用 `URI.create`
- `search/EsSearchClient`：索引 ensure/按文档删/BM25 检索
- `search/MilvusClientWrapper`：collection ensure（schema 仅 chunk_id+document_id 两个字段）、insert、按文档删、向量检索
- `storage/MinioStorage`：upload/download/presign/statSize/statPrefixSize/deleteObject/deletePrefix；md/txt 强制 `charset=utf-8`
- `etcd/EtcdService`：业务动态配置中心。`RetrievalConfig`（vectorTopN/keywordTopN/topK/finalContextTopK/rerankTopN/maxContextTokens/rrfK/lowConfidenceThreshold）从 etcd `/rag/config/global/retrieval` 热更，yml 仅兜底
- `mq/IngestPublisher`：投递 parse/reindex 消息（direct exchange `rag.ingest`）
- `tool/WeatherClient`、`tool/TavilyClient`：外部工具 HTTP 调用，出站受 SSRF + 出站确认双层管控

### 2.4 鉴权链路

```
请求（除 /api/v1/auth/login、/actuator、/error 白名单）
  → JwtAuthFilter（OncePerRequestFilter）
      Authorization: Bearer <jwt>
      claims: sub=userId, tid=tenantId, ut=userType, name；jti 支持 Redis 黑名单（rag:jwt:bl:{jti}）
      X-Tenant-Id 头必须与 tid 一致（平台管理员除外）
      → TenantContext.set(Session)（ThreadLocal，finally 必 clear）
  → Controller 内 AuthGuard.requirePlatform()/requireTenantAdmin() 或自定义归属校验
```

角色：`0` 平台管理员（超级租户 `000000`）、`1` 租户管理员、`2` 普通用户。普通用户菜单由前端 `RequireMenu` + 后端 `UserManageService.requireMenu` 双重校验。

### 2.5 config

`DataInitializer`（首次启动种超级租户 + admin/admin123）、`MinioConfig`、`MybatisPlusConfig`（分页插件）、`RabbitMqConfig`（拓扑见 §4.3）、`WebCorsConfig`。

---

## 3. rag-worker 逐包导读

根包 `com.rag.worker`，启动类 `RagWorkerApplication`，`spring.main.web-application-type=none`。

### 3.1 consumer / mq

- `IngestConsumer`：`@RabbitListener(rag.ingest.parse)`，手动 ack。流程：反序列化 → 校验 task=PENDING → `TenantMqSemaphore.tryAcquire`（租户并发超限 nack requeue + sleep 2s）→ RUNNING → `PipelineProcessor.process` → ack；失败走 `handleFailure`：retry_count+1，≤max-retry(3) 发延迟重试队列（基础延迟 10s × 次数），超限置 FAILED + 文档 FAILED + DLQ
- `mq/RetryPublisher`：发 retry（per-message TTL）/ dlq；`IngestMessage` record（taskId/type/tenantId/kbId/documentId/objectKey）
- `mq/TenantMqSemaphore`：租户级 MQ 并发额度（取自租户 maxMqConcurrency）
- `config/RabbitConfig`：与 API 侧同构声明 exchange/queue（幂等）

### 3.2 pipeline —— 文档处理核心

**`PipelineProcessor`** 两种流程：
- `PARSE`：PARSING(5→20) → `ParseService.parse` → CHUNKING(25→40) 路由策略 plan + `persistPlans` → `indexAll`（EMBEDDING 45→78，INDEXING 85）→ READY 100
- `REINDEX`：保留全部切片（含 MANUAL），仅重嵌 + 双写重建

`indexAll` 细节：只取 `chunk_type=CHILD and status!=DELETED`；空切片分支负责清掉 ES/Milvus 残留；Embedding 按每批 10 条分组；Milvus 先 deleteByDocument 再 insert；ES 同样先删后写。embedding 文本做增强：`文档：{文件名}\n章节：{section_path}\n类型：{contentType}\n\n{content}`。

`persistPlans`：重解析只删 `status=AUTO` 旧行（保留 MANUAL）；parentChild 计划先插 PARENT（parentContent/sectionTitle/sectionPath/contentHash），再插关联 CHILDREN（parent_chunk_id 回填）；独立计划直接插 CHILD。

**`ParseService`**（扩展名分派）：
- txt/md：直读
- docx/xlsx：Apache POI 5.2.5
- pdf：PDFBox 2.0.31 文本层；扫描页/文本稀疏页渲染位图 → Tess4J OCR（`rag.ocr.tessdata` 留空则降级跳过）
- html/htm：jsoup 去 script/style，DOM 转 markdown 结构文本
- png/jpg/jpeg：走 VISION 模型描述（未配置则降级）
- 输出 `ParseOutcome(pages, warnings)`，产物 JSON 写 MinIO `derived/{version}/`

**`TokenCounter`**：近似计数（中文 1 字 1 token，ASCII 连续段 ceil(len/4)），无第三方 tokenizer。

### 3.3 pipeline.chunk —— 15 策略

SPI 契约：`ChunkStrategy.mode()` 返回 `ChunkMode`；`plan(ChunkContext)` 返回 `List<ChunkPlan>`。`ChunkPlan` 是显式 record（parentChild 标志、parentContent、sectionTitle/Path、contentHash、children 列表），不用 null 魔法。`ChunkSupport` 是各策略共享工具（打包、重叠、表格转 JSON/摘要等）。

| 模式 | 策略类 | 要点 |
|---|---|---|
| FIXED_SIZE | FixedSizeStrategy | 定长切分 |
| RECURSIVE | RecursiveStrategy | 分隔符层级递归（默认 \n\n/\n/。？！；，） |
| PARAGRAPH | ParagraphStrategy | 段落聚合 |
| SENTENCE | SentenceStrategy | 句子粒度 |
| SEMANTIC | SemanticStrategy | embedding 相邻相似度断点 |
| STRUCTURE | StructureStrategy | docx 等结构化文档（标题树） |
| MARKDOWN | MarkdownStrategy / MarkdownLikeStrategy | md 标题层级 |
| HTML | HtmlStrategy | HTML DOM 结构 |
| PDF_LAYOUT | PdfLayoutStrategy | PDF 版式、页眉页脚去除、图片页 Vision/OCR |
| TABLE | TableStrategy | 表格块：table_json + table_summary，大表续片 |
| QA | QaStrategy | 问答对格式 |
| PARENT_CHILD | ParentChildStrategy | 主力：父子双粒度（KB 参数 parent/child/overlap） |
| SLIDING_WINDOW | SlidingWindowStrategy | 滑窗 |
| CODE | CodeStrategy | java 用 JavaParser AST；py/go/ts/js 启发式 |
| AUTO | （无 bean）`ChunkStrategyRouter` | pdf→PDF_LAYOUT，代码扩展名→CODE，其余→PARENT_CHILD |

`ChunkParams`：parentChunkSize（默认 2000）/ childChunkSize（500）/ childOverlap（80）/ separators，均可被 knowledge_base 行覆盖。

### 3.4 infrastructure（worker 侧）

- `llm/EmbeddingClient`：OpenAI 兼容 `POST {base}/embeddings`，body 数组每批 ≤10，按 index 排序还原 float[]，60s 读超时
- `llm/VisionClient`：图片理解（多模态 chat）
- `ocr/OcrService`：Tess4j 封装，独立 rag-ocr 线程池
- `search/EsIndexer` / `search/MilvusIndexer`：索引写入侧（与 API 的检索侧对应）
- `storage/MinioStorage`：worker 侧同构副本
- `etcd/WorkerRegistry`：worker 实例注册/心跳到 etcd
- persistence：worker 自己的 6 个实体（Chunk/Document/KnowledgeBase/Model/PipelineTask/Tenant），仅映射流水线需要的列

---

## 4. 关键端到端流程

### 4.1 上传 → 可检索

见 [AGENTS.md §4.1](AGENTS.md)。文档状态机：`PARSING → CHUNKING → EMBEDDING → INDEXING → READY`，任何阶段失败 `FAILED`（error_msg 截断 900 字），前端 KbDetail 用 status+progress 推断步骤条。

### 4.2 问答检索（RetrievalService 详解）

1. 取租户启用的 CHAT/EMBEDDING/RERANK 模型与 `EtcdService` 检索参数
2. Milvus 向量召回 vectorTopN（默认 20）+ ES BM25 keywordTopN（20），两路各得 chunkId 列表
3. **RRF 融合**：score = Σ 1/(rrfK + rank)，rrfK=60；融合后取 topK=8
4. **Rerank（可选）**：拉取候选 chunk 文本调 RERANK 模型 `/rerank`，按 relevance_score 重排截到 rerankTopN=5；未配置模型或调用异常 → 直接沿用 RRF 顺序（warn 日志，不失败）
5. **Parent 扩展**：CHILD 命中回溯 parent 内容，按 parent 单元去重
6. **Token Budget**：按排序顺序累加近似 token，超过 maxContextTokens=12000 即截断
7. citations 带回得分（含可空 rerankScore）供前端展示来源

低置信度（最高分 < lowConfidenceThreshold=0.35）时编排层可走兜底话术/工具分支。

### 4.3 RabbitMQ 拓扑

```
direct exchange: rag.ingest（API/Worker 双侧声明，持久化）
  rk parse   → rag.ingest.parse    主队列（PARSE 与 REINDEX 同队列）
  rk reindex → rag.ingest.parse
  rk retry   → rag.ingest.retry    单消息 TTL，死信回 exchange rk=parse
  rk dlq     → rag.ingest.dlq      超限终态
```

### 4.4 文生图

`ImageController.generate` → `ImageGenerationService`：菜单校验 → 启用 IMAGE 模型（base/key/model 留空回退 DashScope 官方 / LLM key / z-image-turbo）→ 同步生成 → 下载 PNG → checkStorage → MinIO → generated_image 落库。下载走后端鉴权端点（跨域 a[download] 与 MinIO CORS 限制）。

---

## 5. 数据模型速查

建表脚本：`rag-api/src/main/resources/db/migration/rag_demo.sql`（手动执行，无 ORM 自动建表）。

| 表 | 作用 | 关键字段/约束 |
|---|---|---|
| tenant | 租户 | tenant_code 唯一 `^[A-Za-z0-9_]{10,20}$` 不可改；配额四件套 |
| sys_user | 用户 | tenant_id+username；user_type 0/1/2；status；password_hash |
| sys_menu / sys_tool | 菜单/工具目录 | code 唯一 |
| tenant_menu | 租户开通的菜单 | tenant+menu |
| user_feature | 用户功能授权 | user_id + feature_type(MENU/TOOL) + code |
| user_kb / user_prompt | 用户数据授权 | KB 与提示词模板 |
| knowledge_base | 知识库 | es_index、milvus_collection 各 KB 独立；chunk_strategy；parent/child/overlap/separators |
| document | 文档 | object_key、file_size、mime_type、status、progress、page_count、warning/error_msg |
| chunk | 切片 | chunk_type(PARENT/CHILD)、parent_chunk_id、status(AUTO/MANUAL/DELETED)、section_title/path、content、content_hash、page、seq、content_type(TEXT/TABLE/IMAGE/CODE) 及多模态字段 |
| pipeline_task | 流水线任务 | type(PARSE/REINDEX)、status(PENDING/RUNNING/SUCCESS/FAILED)、retry_count、payload、error_msg |
| prompt_template | 提示词 | 归属 kb_id；is_default |
| model | 模型池 | tenant+type+enabled；base_url/model/api_key(脱敏返回)；temperature/top_p/max_tokens/embedding_dim |
| tool_config | 租户工具 | enabled + 凭证 |
| chat_session / chat_message | 会话/消息 | 均按 tenant_id+user_id 隔离；message 存 token_usage |
| generated_image | 生成图历史 | user_id、object_key、file_size、prompt/model/size/seed/status |

MinIO 对象布局：
- 原文件：`{tenantId}/{kbId}/{yyyy-MM-dd}/{uuid}.{ext}`
- 生成图：`{tenantId}/generated/{userId}/{uuid}.png`
- 派生产物：`derived/{versionId}/parsed.json`、`ocr.json` 等

---

## 6. 前端导读（knowledge-console）

- 入口 `main.tsx` → `App.tsx`：路由守卫三层 `RequireAuth`（有无 token）/ `RequireUserType`（角色）/ `RequireMenu`（普通用户菜单码）；登录后 HomeRedirect 按角色落地
- 页面：
  - 平台管理员：`TenantsPage`（租户管理）
  - 租户管理员：`ModelConfigPage`（模型池，类型含 CHAT/VISION/EMBEDDING/IMAGE/RERANK）、`KnowledgeBasesPage`+`KbDetailPage`（文档上传、进度、切片表）、`PromptPage`、`ToolConfigPage`、`UsersPage`（功能权限/数据授权两个勾选弹窗、账号启停开关）、`QuotaPage`
  - 通用：`ChatPage`（SSE 打字机 + citations）、`ImageStudioPage`（文生图历史 + 弹窗详情/下载）
- `api/client.ts`：axios 实例，请求拦截注入 Bearer 与租户头，401 清登录态；`api/*.ts` 一资源一文件；类型集中 `api/types.ts`
- `store/auth.ts`：Zustand 持久化 token/user（menuCodes 用于前端菜单与按钮显隐）
- 代理：vite.config.ts 仅代理 `/api → http://localhost:8080`，常规请求与 SSE 同源无跨域
- SSE：`@microsoft/fetch-event-source` 手动处理事件流，事件名与 §2.2 一致

---

## 7. 配置索引（application.yml）

rag-api `rag.*`：`jwt.secret/ttl-hours`、`minio.*`、`milvus.*`、`etcd.endpoints/enabled`、`mq.exchange/queue/retry-queue/dlq-queue`、`retrieval.*`（vector/keyword/top-k/final-context-top-k/max-context-tokens/rrf-k/low-confidence-threshold）、`llm.timeout-seconds`、`chat.sse-timeout-seconds`。

rag-worker 额外：`ocr.tessdata/langs`、`es.analyzer`、`worker.max-retry/retry-base-delay-ms`。

所有主机默认 `192.168.1.200`，库名默认 `rag_demo`，均支持环境变量覆盖。

---

## 8. 修改代码时的检查清单

- 新增接口：Controller 路由挂 `/api/v1` → DTO 加到 `Dtos.java` → Service 落 `application/` → 归属/租户校验 → 前端 `api/*.ts` + `types.ts` + 页面
- 新增切片策略：实现 `ChunkStrategy`（@Component，mode 返回新枚举）→ `ChunkMode` 加枚举 → 必要时改 Router AUTO 映射 → 补 `ChunkPipelineTest`
- 改 chunk 表：同步四处——rag_demo.sql、api 实体、worker 实体、embedding/检索读取逻辑；**不得加 tokenCount**
- 改模型调用：新类型先在 `ModelService.TYPES` 注册，失败降级策略与日志要明确
- 改鉴权：过滤器、AuthGuard、前端路由守卫、菜单/工具授权四处一起看
- 验证：`mvn -f rag-api/pom.xml clean compile -DskipTests`、`mvn -f rag-worker/pom.xml clean test`、前端 `npm run typecheck`
