# 多租户 RAG 知识库系统架构与产品规格说明书 (PRD & SDD)

## 1. 产品概述

本系统是一个基于大语言模型（LLM）的企业级多租户 RAG（检索增强生成）问答系统。系统支持多租户逻辑与数据的严格隔离，具备完善的知识源管理、基于 Worker 机制的异步数据预处理流水线、混合检索（关键词+向量）能力，并集成了大模型流式响应（SSE）、外部工具（天气查询、Tavily 搜索），旨在提供“打字机”般流畅且来源可溯的智能问答体验。



## 2. 技术栈与系统架构

### 2.1 前端交互层 (Frontend)

- **核心框架**: React


- **状态管理**: Zustand (负责轻量级全局状态、多租户状态隔离及流式问答状态累加)


- **网络通信**:


  - 常规请求：Axios (统一拦截器，注入 Tenant-ID 与 Token)


  - 流式问答：原生 Fetch API / SSE 专用库（如 `@microsoft/fetch-event-source`），处理 Server-Sent Events。


- **UI 组件库**: Ant Design (构建企业级管理后台与响应式问答界面)



### 2.2 后端服务层 (Backend)

- **核心语言与框架**: Java (Spring Boot / Spring Cloud 体系)


- **网关配置**: Nginx / Spring Cloud Gateway (需配置关闭 SSE 相关的缓冲区 `proxy_buffering off` 并调整长连接超时时间)


- **服务注册与动态配置**: etcd



### 2.3 数据与中间件层 (Infrastructure)

- **关系型数据库**: MySQL (存储租户信息、模型参数、知识库与文档元数据)


- **对象存储**: MinIO (持久化存储 PDF、Word、Excel、图片等原始文件及离线切片)


- **向量数据库**: Milvus (存储文本 Embedding 向量，执行语义相似度检索)


- **全文搜索引擎**: Elasticsearch (构建倒排索引，执行基于 BM25 的关键词匹配)


- **缓存与分布式锁**: Redis (Token 管理、高频数据缓存、SSE 连接并发限流)


- **消息队列**: RabbitMQ (用于解耦文件解析、OCR、视觉大模型处理、向量化等高耗时 Worker 任务)



## 3. 角色与权限设计 (RBAC)

### 3.1 平台管理员 (Platform Admin)

系统最高权限持有者，负责全局基础设施与商业化运营管理。



- **租户生命周期管理**: 创建、停用、删除租户（Tenant），分配初始租户管理员账号。


- **资源配额分配**: 配置各租户的 MinIO 存储上限、MQ 任务并发额度、LLM Token 消耗阈值及最大并发 SSE 连接数。



### 3.2 租户管理员 (Tenant Admin)

负责特定租户内部的知识资产运营与模型调优，数据在租户间严格隔离。



- **模型参数管理**: 配置该租户的 LLM 接口参数（如 Temperature、Top-P、Max Tokens）及指定的 Embedding 模型。


- **知识库与存储库管理**: 创建知识库（Collection），自动绑定隔离的 Milvus 集合与 ES 索引别名。


- **文档与切片管理**: 上传文档，定义切片策略（Chunk Size、Overlap、分隔符），并支持对自动解析失败的切片进行人工增删改。


- **工具集配置**: 灵活启停当前租户的天气查询、Tavily 联网搜索等外部 Agent 工具。



## 4. 核心数据流水线与功能模块

### 4.1 知识摄入与异步 Worker 流水线

所有上传的知识文件统一落盘至 MinIO，按 `/{tenant_id}/{knowledge_base_id}/{date}/` 路径实现物理隔离。主服务生成解析 Task 发送至 MQ，由后台 Worker 消费并执行：



1. **多格式解析**:


   - 常规文本：Markdown, Text, Word


   - 结构化数据：Excel (提取表格转化为结构化文本/CSV 切片)


2. **视觉与复杂排版处理**:


   - PDF 与图片：调用 OCR 引擎提取文字。


   - 图表图像：调用视觉大模型（Vision Model）生成图文描述。


3. **分块与向量化 (Chunking & Embedding)**: 根据租户策略进行文本切片，并调用大模型生成高维向量。


4. **双写索引化**: 向量数据及 Chunk ID 写入 **Milvus**；原始文本与文档名、页码等元数据写入 **Elasticsearch**。



### 4.2 混合检索模块 (Hybrid Search)

为了兼顾精准匹配与语义泛化：



- **向量查询**: Milvus 余弦相似度计算，处理上下文语义与同义词。


- **关键词查询**: ES 倒排索引检索，精准召回特定专有名词、序列号。


- **重排融合 (Reranking)**: 合并两路召回结果，使用重排算法（如 RRF）提取 Top-K 切片。



### 4.3 Agent 工具路由

在进行知识库检索前，通过意图识别触发外部工具：



- **天气查询**: 识别气象意图与地域实体，调用外部 Weather API，将天气数据注入大模型上下文。


- **Tavily 联网搜索**: 当知识库召回置信度过低或用户明确要求“最新资讯”时，动态调用 Tavily API 获取实时背景信息。



## 5. SSE 流式问答交互设计 (Streaming Q\&A)

为防止大模型生成长文本时的阻塞，问答接口采用 Server-Sent Events (SSE) 协议进行流式数据下发。



### 5.1 事件契约定义 (Event Types)

服务端与前端约定以下标准事件格式：



- `event: search_start`: 通知前端已开始检索内部知识库或调用 Tavily/天气工具。UI 呈现“正在思考/检索中...”。


- `event: search_result`: 优先下发召回的来源元数据（Citations，包含所属文档、预签名查看链接等）。UI 可提前在侧边栏渲染参考文献。


- `event: message`: 持续下发 LLM 生成的增量文本片段 (Chunk)。UI 驱动 Markdown 引擎实时增量渲染。


- `event: error`: 处理过程中发生异常（限流、Token 耗尽）。UI 呈现错误提示并终止当前生成。


- `event: done`: 生成结束标记。前端据此主动断开长连接。



### 5.2 问答链路时序

1. **建立连接**: React 前端通过 Fetch 发起 POST 请求，携带提问内容及 `Tenant-ID`，并在 Header 中声明接受 `text/event-stream`。


2. **鉴权与异步处理**: Java 后端（通过 `SseEmitter` 或 WebFlux）建立连接，迅速释放主线程，将混合检索任务丢入线程池并发执行。


3. **结果组装与流式推送**:


   - 后端将 ES/Milvus 的召回结果与工具返回结果组装为 RAG Prompt。


   - 调用 LLM 接口（开启 stream 模式），逐块接收模型响应并通过 SSE 推送 `message` 给前端。


4. **来源追溯 (Citations)**: 前端结合 `search_result` 下发的元数据，将大模型引用的序号（如 `[1]`）渲染为可点击的交互角标，用户点击可直接预览 MinIO 中的源文件片段。



### 5.3 容灾与限流策略

- **连接保护**: 结合 Redis 对 SSE 长连接进行 Tenant 级别限流，防止单一租户耗尽 Tomcat/Netty 并发数；后端设置严格的 `SseEmitter` 超时时间（如 60s）。


- **优雅降级**: 若 SSE 连接异常中断，前端保留已生成的半成品文本，由用户手动触发“重新生成”，避免静默自动重连导致 Token 严重浪费。
