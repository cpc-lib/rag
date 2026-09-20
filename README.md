# 多租户 RAG 知识库系统

一套面向企业的多租户检索增强生成（RAG）平台，提供知识库管理、文档解析切片、向量检索、智能问答、AI 画图、Agent 工具调用等完整能力。系统采用 API + Worker 异步流水线 + 前端控制台的分层架构，支持租户隔离、配额管控与细粒度的用户授权。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.5 + Java 17 |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.4（Flyway 迁移） |
| 缓存 | Redis 7.4 |
| 消息队列 | RabbitMQ |
| 对象存储 | MinIO |
| 向量数据库 | Milvus 2.5.4 |
| 全文检索 | Elasticsearch 8.14.3 |
| 元数据/配置中心 | etcd v3.5.18 |
| 前端 | React 18 + TypeScript + Vite 5 + Ant Design 5 |
| 状态管理 | Zustand |
| 文档解析 | Apache POI、PDFBox、Tess4J(OCR)、jsoup、JavaParser |

## 目录结构

```
rag/
├── rag-api/                # REST + SSE API 服务（端口 8080）
│   └── src/main/java/com/rag/api/
│       ├── application/    # 应用服务层（鉴权、问答编排、检索、知识库等）
│       ├── interfaces/     # Controller、DTO、安全过滤器、全局异常
│       ├── infrastructure/ # MinIO / Milvus / ES / etcd / LLM / MQ 客户端
│       ├── common/         # 统一返回、异常、租户上下文
│       └── config/         # 数据初始化、CORS、MQ 等配置
├── rag-worker/             # 异步流水线 Worker（非 Web 进程）
│   └── src/main/java/com/rag/worker/
│       ├── consumer/       # MQ 消费入口
│       ├── pipeline/       # 解析、切片、嵌入、索引编排
│       │   └── chunk/      # 15 种切片策略（策略模式）
│       └── infrastructure/ # OCR、Embedding、Vision、ES/Milvus 索引器
├── knowledge-console/      # 前端控制台（端口 5173）
│   └── src/
│       ├── pages/          # 登录、问答、知识库、用户管理等页面
│       ├── api/            # 后端接口封装
│       └── store/          # 认证状态
└── docker-compose.yml      # 中间件编排
```

## 核心功能

- 多租户隔离：超级平台租户（000000）+ 业务租户，数据按 tenant_id 隔离，跨租户访问返回 404 防止资源泄漏。
- 知识库与文档流水线：上传文档后经 MQ 异步执行 解析 -> 切片 -> 向量化 -> 索引（Milvus + ES），支持 15 种切片策略：AUTO、FIXED_SIZE、RECURSIVE、PARAGRAPH、SENTENCE、SEMANTIC、STRUCTURE、MARKDOWN、HTML、PDF_LAYOUT、TABLE、QA、PARENT_CHILD（父子切片）、SLIDING_WINDOW、CODE。
- 混合检索：向量检索（Milvus）+ 关键词检索（ES）通过 RRF 融合，可配置 topN / topK / 置信度阈值。
- 智能问答：SSE 流式输出，支持引用来源、会话管理、提示词模板。
- AI 画图：基于 z-image-turbo 的文生图，生成图片持久化到 MinIO 并提供下载。
- Agent 工具调用：天气查询、联网搜索（Tavily），工具可用性由租户总开关 + 用户授权双层控制。
- 用户授权：菜单授权、工具授权、知识库及提示词授权，均通过列表勾选弹窗按用户管理。
- 安全：JWT 鉴权、管理员强制 MFA（TOTP + 恢复码）、密码 BCrypt、SSRF 与出站策略校验。
- 配额管控：存储上限、MQ 并发额度、月度 LLM Token 阈值、SSE 最大连接数。
- 动态配置：检索参数等可通过 etcd 热更新。

## 环境要求

- JDK 17
- Maven 3.8+
- Node.js 18+（推荐 20+）
- Docker 与 Docker Compose
- 至少 8GB 可用内存（Milvus 与 ES 开销较大）

## 快速开始

### 1. 启动中间件

```bash
docker compose up -d
```

启动的服务：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | root / rag123456 |
| Redis | 6379 | |
| MinIO | 9000 / 9001 | minioadmin / minioadmin123 |
| etcd | 2379 | |
| Elasticsearch | 9200 | |
| Milvus | 19530 / 9091 | |

RabbitMQ 默认使用本地或外部实例，docker-compose.yml 中已注释。如需容器化启动，取消 rabbitmq 服务段的注释即可。

### 2. 构建后端

API 与 Worker 为独立模块，分别构建：

```bash
# 构建 API
cd rag-api
mvn clean package -DskipTests

# 构建 Worker
cd ../rag-worker
mvn clean package -DskipTests
```

### 3. 运行后端

```bash
# 启动 API（端口 8080）
java -jar rag-api/target/rag-api-1.0.0.jar

# 启动 Worker（无 Web 端口）
java -jar rag-worker/target/rag-worker-1.0.0.jar
```

也可在 IntelliJ IDEA 中直接运行 RagApiApplication 与 RagWorkerApplication。API 与 Worker 必须使用相同的 JWT 密钥以保证加解密一致。

### 4. 运行前端

```bash
cd knowledge-console
npm install
npm run dev
```

前端默认运行在 http://localhost:5173，Vite 已将 /api 代理到 http://localhost:8080。

### 5. 登录

首次启动后自动创建超级平台管理员账号：

- 账号：admin
- 密码：admin123

管理员首次登录需完成 MFA 绑定（TOTP 或恢复码）。测试环境可配置 rag.security.mfa-bypass-code 为 123456 绕过绑定，生产环境务必移除该配置。

## 配置说明

后端配置通过环境变量覆盖 application.yml 默认值，常用变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| MYSQL_HOST | 192.168.1.200 | MySQL 主机 |
| MYSQL_PORT | 3306 | MySQL 端口 |
| MYSQL_DB | rag | 数据库名 |
| MYSQL_USER | root | 数据库用户 |
| MYSQL_PASSWORD | rag123456 | 数据库密码 |
| MIDDLEWARE_HOST | 192.168.1.200 | Redis/RabbitMQ/ES/Milvus/etcd/MinIO 统一主机 |
| RABBITMQ_USER | guest | RabbitMQ 用户 |
| RABBITMQ_PASSWORD | guest | RabbitMQ 密码 |
| MINIO_USER | minioadmin | MinIO 访问密钥 |
| MINIO_PASSWORD | minioadmin123 | MinIO 密钥 |
| RAG_JWT_SECRET | rag-dev-secret-key-change-me-32bytes!! | JWT 签名密钥（生产务必修改） |
| RAG_OCR_TESSDATA | 空 | Tesseract 训练数据目录，留空则 OCR 降级跳过 |
| RAG_ES_ANALYZER | standard | ES 分词器 |

当 MySQL 不可用时，API/Worker 可切换到 H2 文件数据库（backend/data/uismock.*，AUTO_SERVER 共享），便于本地快速调试。

## 模型配置

每个租户在「模型参数」页面独立配置：

- LLM：OpenAI 兼容接口地址、API Key、模型名、温度、top_p、max_tokens
- Embedding：向量化模型（支持 DashScope 兼容端点，批量不超过 10 条）
- Vision：视觉大模型（可选，用于图文理解）
- Image：文生图模型（z-image-turbo，Base URL 需为裸主机，不含 /compatible-mode/v1 后缀）

## 开发约定

- Maven 构建需显式设置 UTF-8 编码（project.build.sourceEncoding / project.reporting.outputEncoding）。
- maven.compiler.parameters 必须为 true，否则 Spring 参数名注入会导致 RabbitMQ Bean 歧义。
- API 与 Worker 共用同一套 Flyway 迁移脚本（仅 API 执行迁移，Worker 禁用 Flyway）。
- LLM 客户端使用 ConnectionProvider.newConnection() 避免连接池陈旧连接导致 TCP RST。
- SSE 响应解析直接处理 payload，不检查 data: 前缀（Spring SSE reader 已剥离）。
- Embedding 调用需将超过 10 条的批次拆分。

## 许可证

本项目为内部使用，未指定开源许可证。
