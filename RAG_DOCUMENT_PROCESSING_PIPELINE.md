# RAG 文档解析、切片与检索处理流水线

## 1. 文档处理整体流程

企业级 RAG 系统不应该采用简单的：

```text
文档
  ↓
提取纯文本
  ↓
固定长度切片
  ↓
Embedding
  ↓
向量检索
```

更推荐采用完整的结构化、多模态处理流水线：

```text
1. 文档分类
        ↓
2. Layout / Structure Parser
        ↓
3. 去页眉页脚
        ↓
4. Heading Reconstruction
        ↓
5. 图片抽取
        ├── OCR
        └── Vision
        ↓
6. 表格抽取
        ├── Markdown
        ├── JSON
        └── Summary
        ↓
7. Parent Chunk
        ↓
8. Child Chunk
        ↓
9. Semantic Boundary Refinement
        ↓
10. Embedding
        ↓
11. Milvus
        +
    Elasticsearch
        ↓
12. Hybrid Search
        ↓
13. RRF
        ↓
14. Qwen3-Rerank
        ↓
15. Parent Context Expansion
        ↓
16. LLM
```

整体架构可以进一步表示为：

```text
                          Document
                             │
                             ▼
                   ┌─────────────────┐
                   │ Document Router │
                   └────────┬────────┘
                            │
                            ▼
                Layout / Structure Parser
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
           Text           Image           Table
             │              │              │
             │         ┌────┴────┐    ┌────┴────┐
             │         │         │    │         │
             │        OCR      Vision Markdown JSON
             │         │         │    │         │
             └─────────┴────┬────┴────┴─────────┘
                            │
                            ▼
                  Structure Reconstruction
                            │
                            ▼
                     Parent Chunk
                            │
                            ▼
                      Child Chunk
                            │
                            ▼
                Semantic Boundary Refinement
                            │
                            ▼
                        Embedding
                            │
                ┌───────────┴───────────┐
                │                       │
                ▼                       ▼
             Milvus               Elasticsearch
          Vector Search              BM25
                │                       │
                └───────────┬───────────┘
                            │
                            ▼
                       Hybrid Search
                            │
                            ▼
                            RRF
                            │
                            ▼
                     Qwen3-Rerank
                            │
                            ▼
                 Parent Context Expansion
                            │
                            ▼
                           LLM
```

## 2. 文档分类

首先识别上传文件的类型，然后针对不同文档选择不同解析器和切片策略。

支持类型可包括：

```text
PDF
Word
PPT
Excel
Markdown
TXT
HTML
CSV
JSON
Image
```

推荐策略：

| 文件类型 | 推荐解析策略 |
|---|---|
| PDF | Layout-aware Parser |
| Word | Heading + Paragraph |
| PPT | Slide-aware |
| Excel | Sheet + Table-aware |
| Markdown | Heading-aware |
| TXT | Recursive / Semantic |
| HTML | DOM-aware |
| CSV | Header + Row Group |
| JSON | JSON Path |
| 图片 | OCR + Vision |

统一转换为内部文档模型：

```json
{
  "documentId": "xxx",
  "fileName": "payment-design.pdf",
  "fileType": "PDF",
  "pages": [],
  "metadata": {}
}
```

## 3. Layout / Structure Parser

目标是解析文档中的布局结构，而不是简单提取纯文本。

建议识别的 Block 类型：

```text
TITLE
HEADING
PARAGRAPH
LIST
TABLE
IMAGE
CAPTION
CODE
FORMULA
HEADER
FOOTER
PAGE_NUMBER
```

统一模型示例：

```json
{
  "type": "PARAGRAPH",
  "page": 10,
  "bbox": {
    "x": 120,
    "y": 300,
    "width": 800,
    "height": 160
  },
  "content": "退款审核通过之后调用支付渠道退款接口。"
}
```

## 4. 去页眉页脚

PDF 中页眉页脚如果不清理，会产生大量重复文本，污染 Embedding、BM25 和 Rerank。

可以根据以下条件判断：

```text
文本内容相同
+
页面坐标位置接近
+
超过一定页面重复出现
```

例如出现比例超过 60% 时，可判定为 HEADER 或 FOOTER。

页面号本身不需要进入 Embedding，但页码必须保留到 metadata：

```json
{
  "pageStart": 10,
  "pageEnd": 11
}
```

## 5. Heading Reconstruction

标题结构对于 RAG 非常重要。

例如：

```text
支付系统
└── 退款管理
    └── 退款审核
        └── 支付宝退款
```

可以生成：

```text
sectionPath = 支付系统 / 退款管理 / 退款审核 / 支付宝退款
```

Embedding 时建议带上标题上下文：

```text
支付系统 > 退款管理 > 退款审核 > 支付宝退款

审核通过之后调用支付宝退款接口。
```

## 6. 图片抽取

PDF 中图片不能简单忽略。图片通常包含：

```text
架构图
流程图
UML
截图
业务流程
组织结构
数据统计图
产品图片
```

建议图片采用双通道：

```text
Image
  │
  ├── OCR
  │
  └── Vision
```

## 7. OCR

OCR 的职责主要是提取图片中真实存在的文字。

例如：

```text
Gateway
Order Service
Payment Service
```

OCR 更适合：

```text
扫描件
截图
合同
发票
流程图文字
图片表格
```

## 8. Vision

Vision 模型负责理解图片表达的业务含义。

例如架构图可以转换为：

```text
该图描述支付系统架构。

外部请求首先经过 Gateway。
Gateway 将订单请求转发到 Order Service。
Order Service 与 Payment Service 通信。
Payment Service 使用 Redis 做缓存，
通过 RabbitMQ 处理异步消息，
并将支付订单写入 MySQL。
```

相比纯 OCR，Vision 对 RAG 更有价值。

图片最终建议组合：

```text
Caption
+
OCR
+
Vision Description
+
Nearby Context
```

## 9. 表格抽取

表格不应该按照普通段落处理。

建议同时保存 Markdown 和 JSON，并额外生成自然语言 Summary。

Markdown 示例：

```markdown
| 商品 | 数量 | 单价 |
|---|---:|---:|
| 手机 | 2 | 5000 |
| 耳机 | 1 | 300 |
```

JSON 示例：

```json
{
  "headers": [
    "商品",
    "数量",
    "单价"
  ],
  "rows": [
    ["手机", "2", "5000"],
    ["耳机", "1", "300"]
  ]
}
```

Summary 示例：

```text
该表格描述订单中的商品信息。

手机购买数量为 2 件，单价 5000 元。
耳机购买数量为 1 件，单价 300 元。
```

## 10. 大表格切片

大表格不能整体作为一个 Chunk。

建议：

```text
Table
  ↓
Row Group
```

例如每 50 行一个 Chunk，并重复表头：

```text
商品 | 日期 | 数量 | 金额

手机 | 2026-01-01 | 1 | 5000
电脑 | 2026-01-01 | 1 | 8000
...
```

下一块依旧带表头。

这种方式称为：

```text
Header Repeat Strategy
```

## 11. Parent Chunk

Parent Chunk 用于保留完整上下文。

推荐大小：

```text
1500 ~ 3000 tokens
```

例如：

```yaml
parent:
  target_tokens: 2000
  max_tokens: 3000
```

## 12. Child Chunk

Child Chunk 用于提高召回精准度。

推荐大小：

```text
300 ~ 600 tokens
```

示例：

```yaml
child:
  target_tokens: 500
  min_tokens: 150
  max_tokens: 800
  overlap_tokens: 80
```

Milvus 主要检索 Child Chunk，最终返回给 LLM 时再扩展到 Parent Chunk。

## 13. Parent-Child 数据关系

```text
Document
   │
   ├── Parent 1
   │      │
   │      ├── Child 1
   │      ├── Child 2
   │      └── Child 3
   │
   └── Parent 2
          │
          ├── Child 4
          └── Child 5
```

示例：

```json
{
  "chunkId": "child-001",
  "parentChunkId": "parent-001",
  "documentId": "doc-001",
  "content": "支付宝退款审核通过之后调用渠道退款接口。"
}
```

## 14. Semantic Boundary Refinement

完成结构化切片后，可以进行语义边界优化。

基本思想：

```text
Sentence 1
Sentence 2
Sentence 3

----------------

Sentence 4
Sentence 5
```

通过相邻句 Embedding 计算：

```text
similarity(sentence3, sentence4)
```

如果：

```text
similarity < threshold
```

说明这里可能发生主题变化。

例如：

```yaml
semantic:
  enabled: true
  similarity_threshold: 0.72
```

Semantic Chunking 更适合作为结构化切片后的优化步骤，而不是直接处理整个 PDF。

## 15. Embedding

最终对 Child Chunk 进行 Embedding。

推荐 Embedding 输入：

```text
Document Title
+
Section Path
+
Content Type
+
Chunk Content
```

例如：

```text
文档：
支付系统设计

章节：
支付系统 > 退款管理 > 退款审核

类型：
TEXT

正文：
管理员审核退款申请通过后，
调用支付渠道退款接口。
```

## 16. Milvus

Milvus 负责 Vector Search。

主要存储：

```text
chunk_id
tenant_id
knowledge_base_id
document_id
parent_chunk_id
content
content_type
page
vector
```

用户查询经过 Embedding 后执行 Milvus TopK Search。

例如：

```text
Query
 ↓
Embedding
 ↓
Milvus Top 50
```

## 17. Elasticsearch

Elasticsearch 主要负责：

```text
Keyword Search
BM25
Exact Match
Metadata Filter
```

特别适合：

```text
订单号
产品型号
接口名称
错误码
类名
方法名
专业术语
```

因此推荐采用：

```text
Milvus
+
Elasticsearch
```

## 18. Hybrid Search

查询同时进入两条检索链路：

```text
Query
 │
 ├── Embedding
 │      ↓
 │    Milvus
 │
 └── Keyword
        ↓
    Elasticsearch
```

得到：

```text
Vector Results
+
BM25 Results
```

## 19. RRF

使用 Reciprocal Rank Fusion 合并 Milvus 与 Elasticsearch 的排序结果。

RRF 公式：

```text
RRF Score =
Σ 1 / (k + rank)
```

例如：

```text
k = 60
```

其优势是不需要直接比较 BM25 Score 和 Vector Score。

## 20. Qwen3-Rerank

Hybrid Search 可以先召回：

```text
Top 30 ~ 100
```

例如：

```text
Milvus Top 50
+
Elasticsearch Top 50
↓
RRF
↓
Top 30
↓
Qwen3-Rerank
↓
Top 5 ~ 10
```

Rerank 输入：

```text
Query
+
Candidate Chunk
```

输出相关性 Score。

## 21. Parent Context Expansion

Rerank 找到 Child Chunk 后，不应该直接发送给 LLM，而是根据 parentChunkId 找到 Parent Chunk。

例如：

```text
Child 7
↓
Parent Chunk
↓
必要时 Neighbor Chunk
```

最终得到更完整的上下文。

## 22. Context 去重

多个 Child Chunk 可能属于同一个 Parent：

```text
Child 1 → Parent A
Child 2 → Parent A
Child 3 → Parent B
```

最终需要：

```text
distinct(parentChunkId)
```

避免重复发送：

```text
Parent A
Parent A
Parent B
```

## 23. Context Budget

Parent Expansion 后必须控制 Token 数量。

示例：

```yaml
retrieval:
  candidate_top_k: 50
  rerank_top_k: 10
  final_context_top_k: 5
  max_context_tokens: 12000
```

Context Builder 可以根据：

```text
rerank_score
+
token budget
```

动态选择最终上下文。

## 24. LLM

最终 Prompt 由以下部分组成：

```text
System Prompt
+
Retrieved Context
+
Conversation History
+
User Question
```

建议要求模型：

```text
严格基于知识库内容回答；
如果知识库中不存在答案，则明确说明；
引用内容时返回文档名称、页码和章节。
```

## 25. 推荐 Chunk 数据模型

```text
Chunk
│
├── chunkId
├── tenantId
├── knowledgeBaseId
├── documentId
│
├── parentChunkId
├── chunkIndex
│
├── contentType
│
├── title
├── sectionPath
│
├── content
├── rawContent
│
├── pageStart
├── pageEnd
│
├── bbox
│
├── imageUrl
├── imageCaption
├── ocrText
├── visionDescription
│
├── tableMarkdown
├── tableJson
├── tableSummary
│
├── tokenCount
│
├── embedding
│
└── metadata
```

## 26. Content Type

推荐：

```text
TEXT
TABLE
IMAGE
CODE
FORMULA
LIST
TITLE
```

Java 枚举示例：

```java
public enum ChunkContentType {

    TEXT,

    TABLE,

    IMAGE,

    CODE,

    FORMULA,

    LIST,

    TITLE
}
```

## 27. 推荐 Pipeline 配置

```yaml
rag:

  ingestion:

    pdf:
      layout-detection: true
      remove-header-footer: true
      heading-reconstruction: true
      merge-cross-page-table: true

    image:
      extract-enabled: true
      ocr-enabled: true
      vision-enabled: true
      keep-caption: true
      keep-nearby-context: true

    table:
      extract-enabled: true
      markdown-enabled: true
      json-enabled: true
      summary-enabled: true
      repeat-header: true
      max-rows-per-chunk: 50

  chunk:

    strategy: hierarchical-semantic

    parent:
      target-tokens: 2000
      max-tokens: 3000

    child:
      target-tokens: 500
      min-tokens: 150
      max-tokens: 800
      overlap-tokens: 80

    semantic:
      enabled: true
      similarity-threshold: 0.72

  retrieval:

    vector-top-k: 50
    keyword-top-k: 50
    rrf-top-k: 30
    rerank-top-k: 10
    final-top-k: 5
    max-context-tokens: 12000
```

## 28. 推荐 Worker Pipeline

```text
DocumentUploaded
       │
       ▼
DocumentParseWorker
       │
       ▼
LayoutAnalysisWorker
       │
       ▼
ImageExtractionWorker
       │
       ├── OCR Worker
       │
       └── Vision Worker
       │
       ▼
TableExtractionWorker
       │
       ▼
StructureRebuildWorker
       │
       ▼
ChunkWorker
       │
       ▼
EmbeddingWorker
       │
       ├── Milvus Writer
       │
       └── Elasticsearch Writer
       │
       ▼
DocumentReady
```

大文件可以进一步拆成：

```text
Document
↓
Page Split
↓
Page Workers
↓
Merge
↓
Chunk
↓
Embedding
```

## 29. 推荐文档状态机

```text
UPLOADED
↓
PARSING
↓
PARSED
↓
EXTRACTING
↓
CHUNKING
↓
EMBEDDING
↓
INDEXING
↓
READY
```

失败状态可以细分为：

```text
FAILED

PARSING_FAILED
OCR_FAILED
VISION_FAILED
EMBEDDING_FAILED
INDEX_FAILED
```

建议同时记录：

```text
retry_count
error_code
error_message
failed_stage
```

## 30. 最终企业级 RAG Pipeline

```text
文件上传
   │
   ▼
Document Classification
   │
   ▼
Layout / Structure Parsing
   │
   ├── Text
   ├── Image
   ├── Table
   ├── Formula
   └── Code
   │
   ▼
Header / Footer Removal
   │
   ▼
Heading Reconstruction
   │
   ├── Image
   │     ├── OCR
   │     └── Vision
   │
   ├── Table
   │     ├── Markdown
   │     ├── JSON
   │     └── Summary
   │
   ▼
Structure Normalization
   │
   ▼
Parent Chunk
   │
   ▼
Child Chunk
   │
   ▼
Semantic Boundary Refinement
   │
   ▼
Embedding
   │
   ├───────────────┐
   ▼               ▼
Milvus        Elasticsearch
Vector             BM25
   │               │
   └───────┬───────┘
           ▼
      Hybrid Search
           │
           ▼
          RRF
           │
           ▼
      Qwen3-Rerank
           │
           ▼
   Parent Expansion
           │
           ▼
     Context Dedup
           │
           ▼
     Token Budget
           │
           ▼
          LLM
           │
           ▼
      Answer + Citation
```

## 31. 核心设计原则

1. 先解析结构，再进行切片。
2. 不要把 PDF 简单转换为纯文本。
3. 图片采用 OCR + Vision 双通道处理。
4. 表格同时保存 Markdown、JSON 和自然语言 Summary。
5. Child Chunk 用于精准召回。
6. Parent Chunk 用于提供完整上下文。
7. Embedding 内容应该携带标题和章节路径。
8. Milvus 与 Elasticsearch 共同承担检索。
9. 使用 RRF 融合 Keyword Search 和 Vector Search。
10. 使用 Qwen3-Rerank 进行二阶段排序。
11. Rerank 后重新扩展 Parent Context。
12. 最终 Context 必须进行去重和 Token Budget 控制。
13. 回答必须尽可能附带文档、页码和 Chunk 来源。
14. 文档解析、OCR、Vision、Embedding 和索引建议全部 Worker 化。
15. 整个 Ingestion Pipeline 必须具备幂等、重试、失败恢复和可观测能力。

## 32. 最终目标

企业级 RAG 文档系统最终应该实现：

```text
用户问的是“文字”
→ 可以找到文字。

用户问的是“表格数据”
→ 可以找到表格。

用户问的是“图片中的内容”
→ 可以找到图片。

用户问的是“流程图表达的业务关系”
→ Vision 可以理解。

用户输入精确关键词
→ Elasticsearch 可以找到。

用户使用自然语言描述
→ Milvus 可以找到。

多个结果存在噪声
→ Qwen3-Rerank 可以重新排序。

Chunk 上下文不足
→ Parent Context Expansion 补全。

最终答案
→ 可以追溯到原始文档、页码、图片、表格和章节。
```

最终架构目标不是单纯的：

```text
文档 → Chunk → Vector → LLM
```

而是：

```text
Document Understanding
        +
Structured Chunking
        +
Multimodal Extraction
        +
Hybrid Retrieval
        +
Reranking
        +
Context Reconstruction
        +
Citation
        =
Enterprise RAG
```
