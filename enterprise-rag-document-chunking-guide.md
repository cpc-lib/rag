# 企业级 RAG 知识库文档切片设计指南

## 1. 文档切片的核心目标

在知识库 RAG 系统中，文档切片（Chunking）不是简单地“每 500 字切一次”，而是直接影响以下指标：

- 检索召回率
- 上下文完整性
- 回答准确率
- 引用定位能力
- Embedding 成本
- Milvus / Elasticsearch 存储规模
- Rerank 成本
- 增量更新效率
- 多模态文档处理能力

典型流程：

```text
用户上传文档
   ↓
文档解析
   ↓
结构识别
   ↓
Chunking
   ↓
Embedding
   ↓
Milvus / Elasticsearch
   ↓
Hybrid Search
   ↓
Rerank
   ↓
LLM
```

企业级 RAG 不应该只保留 `Document -> Chunk` 两层，而应设计为：

```text
Document
   ↓
Section
   ↓
Parent Chunk
   ↓
Child Chunk
   ↓
Embedding
```

其中：

- Child Chunk：负责精准检索
- Parent Chunk：负责补充完整上下文
- Section：负责章节结构和引用定位
- Document：负责来源归属和权限隔离

---

## 2. 推荐的数据模型

建议至少设计以下逻辑实体：

```text
Document
Section
Parent Chunk
Child Chunk
Block
```

层级关系：

```text
Document
   │
   ├── Section
   │      │
   │      ├── Parent Chunk
   │      │       │
   │      │       ├── Child Chunk
   │      │       ├── Child Chunk
   │      │       └── Child Chunk
   │      │
   │      └── Parent Chunk
   │
   └── Section
```

建议表结构示例：

```sql
CREATE TABLE document_chunk (
    id BIGINT PRIMARY KEY,

    tenant_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,

    parent_chunk_id BIGINT NULL,

    chunk_index INT NOT NULL,

    chunk_type VARCHAR(32),

    content TEXT,
    embedding_content TEXT,

    content_hash VARCHAR(64),

    token_count INT,
    char_count INT,

    page_start INT,
    page_end INT,

    section_title VARCHAR(512),
    section_path VARCHAR(1000),

    source_type VARCHAR(32),

    vector_id VARCHAR(128),

    metadata JSON,

    created_at DATETIME,
    updated_at DATETIME
);
```

必须贯穿整个链路的字段：

```text
tenant_id
knowledge_base_id
document_id
```

用于：

- 多租户隔离
- 知识库隔离
- 文档级过滤
- 向量检索过滤
- 权限控制
- 数据清理

---

# 3. Chunk Strategy 分类

建议系统至少支持以下策略：

```text
FIXED_SIZE
RECURSIVE
PARAGRAPH
SENTENCE
SEMANTIC
STRUCTURE
MARKDOWN
HTML
PDF_LAYOUT
TABLE
QA
PARENT_CHILD
SLIDING_WINDOW
CODE
AUTO
```

推荐管理员配置：

```text
知识库
  └─ Chunk Strategy
       ├─ Fixed
       ├─ Recursive
       ├─ Semantic
       ├─ Parent-Child
       └─ Auto
```

企业级默认推荐：

```text
AUTO
```

由系统根据文件类型、文档结构和知识库配置自动选择。

---

# 4. Fixed Size 固定长度切片

示例：

```text
chunkSize = 512 tokens
overlap = 64 tokens
```

结构：

```text
Chunk1: A B C D
Chunk2: C D E F
Chunk3: E F G H
```

优点：

- 实现简单
- 速度快
- 性能稳定
- 适合作为 fallback

缺点：

- 容易切断标题与正文
- 容易切断列表
- 容易切断表格
- 容易切断代码块
- 容易破坏完整语义

因此：

> Fixed Size 不建议作为企业知识库默认策略，只适合作为兜底策略。

---

# 5. Recursive Chunking 递归切片

推荐分隔符顺序：

```text
\n\n
↓
\n
↓
。
↓
！
↓
？
↓
；
↓
，
↓
空格
```

逻辑：

```text
优先按段落切
   ↓
仍然过大
   ↓
按句子切
   ↓
仍然过大
   ↓
按标点切
   ↓
仍然过大
   ↓
按 Token 强制切
```

Java 示例：

```java
List<String> separators = List.of(
    "\n\n",
    "\n",
    "。",
    "！",
    "？",
    "；",
    "，",
    " "
);
```

适合：

- TXT
- 普通 Word
- 普通 PDF
- 无明显结构的长文本

---

# 6. Paragraph Chunking 段落切片

逻辑：

```text
Paragraph
   ↓
长度判断
   ↓
太小 → 合并
适中 → 直接生成 Chunk
太大 → Recursive Split
```

推荐参数：

```text
minChunkSize = 150 tokens
maxChunkSize = 800 tokens
```

避免：

- 过小 Chunk
- 过长段落
- 上下文信息不足

---

# 7. Sentence Chunking 句子切片

逻辑：

```text
Sentence1
Sentence2
Sentence3
      ↓
按 Token 聚合
      ↓
Chunk <= MaxTokens
```

推荐：

```text
chunkSize = 300~500 tokens
overlapSentences = 1~3
```

相比字符级 overlap，句子级 overlap 更容易保持自然语义。

---

# 8. Semantic Chunking 语义切片

Semantic Chunking 根据语义变化点切片，而不是按字符或 Token 长度机械切片。

流程：

```text
Sentence / Paragraph
       ↓
Embedding
       ↓
计算相邻语义相似度
       ↓
识别语义突变点
       ↓
生成 Chunk
```

例如：

```text
Redis 内容
Redis 内容
Redis 内容
MySQL 内容
MySQL 内容
```

如果：

```text
sim(S1,S2) = 0.92
sim(S2,S3) = 0.89
sim(S3,S4) = 0.32
```

则：

```text
S3 与 S4 之间作为 semantic break
```

优点：

- 语义完整
- 对长文档效果较好
- 适合高质量知识库

缺点：

- 额外 Embedding 成本
- 文档导入时间更长
- 算法复杂度更高

建议分为：

| 模式 | 策略 |
|---|---|
| FAST | Recursive |
| BALANCED | Structure + Recursive |
| HIGH_QUALITY | Structure + Semantic |

---

# 9. Structure-aware Chunking 结构化切片

企业级 RAG 最推荐的切片方式之一。

例如：

```text
1 支付系统
1.1 支付下单
1.1.1 微信支付
1.1.2 支付宝支付
```

不应该简单每 500 Token 切一次。

应该构建：

```text
H1
 └─ H2
      └─ H3
```

Chunk metadata：

```text
section_path:
支付系统 > 支付下单 > 微信支付
```

Embedding 内容：

```text
支付系统
支付下单
微信支付

正文内容……
```

推荐公式：

```text
Embedding Text
=
Document Title
+
Section Path
+
Chunk Content
```

这样能够显著提高带章节语义查询的召回率。

---

# 10. Markdown 文档切片

推荐流程：

```text
Markdown
   ↓
Heading Tree
   ↓
Code Block 保护
   ↓
Table 保护
   ↓
Paragraph
   ↓
Recursive Chunk
```

例如：

```markdown
# 支付系统

## 微信支付

### Native 支付

正文

### JSAPI 支付

正文
```

生成：

```text
section_path:
支付系统 / 微信支付 / Native支付
```

代码块必须整体保护：

````markdown
```java
public void pay() {
}
```
````

不能从代码块中间切开。

---

# 11. HTML 文档切片

HTML 不建议直接：

```java
Jsoup.text()
```

然后再切片。

应该保留 DOM 语义：

```text
<h1>
<h2>
<h3>
<p>
<ul>
<li>
<table>
<pre>
<code>
<img>
```

统一转换为：

```text
HeadingBlock
ParagraphBlock
ListBlock
TableBlock
CodeBlock
ImageBlock
```

然后再做：

```text
DOM
 ↓
Block Tree
 ↓
Structure Chunk
```

---

# 12. Word 文档切片

推荐使用 Apache POI + Apache Tika。

需要识别：

```text
Heading 1
Heading 2
Heading 3
Paragraph
Table
Image
Header
Footer
```

不要只提取纯文本。

推荐构建内部结构：

```text
DocumentNode

Heading
 ├─ Paragraph
 ├─ Paragraph
 ├─ Table
 └─ Image
```

然后执行 Structure-aware Chunking。

---

# 13. PDF 文档切片

PDF 是最复杂的文件类型。

很多 PDF 没有真正的：

```text
paragraph
table
heading
```

本质是：

```text
文字 + 坐标 + 字体 + 图像
```

因此推荐流程：

```text
PDF
 ↓
Page
 ↓
Layout Detection
 ↓
Block Detection
 ↓
Reading Order
 ↓
Chunking
```

Block 类型：

```text
TitleBlock
TextBlock
TableBlock
ImageBlock
FormulaBlock
HeaderBlock
FooterBlock
```

---

# 14. PDF 页眉页脚处理

大量 PDF 每页都存在：

```text
公司名称
内部资料
第 N 页
```

如果不处理，会产生大量垃圾向量。

推荐识别规则：

```text
固定区域
+
文本高度相似
+
出现于 > 60% 页面
```

然后标记：

```text
HEADER
FOOTER
```

默认不进入 Embedding。

---

# 15. PDF 多栏阅读顺序

论文、报告中常见：

```text
AAAA      DDDD
BBBB      EEEE
CCCC      FFFF
```

错误解析结果可能为：

```text
AAAA
DDDD
BBBB
EEEE
```

正确做法是根据：

```text
x
y
width
height
```

识别 Column Layout。

阅读顺序：

```text
左上
↓
左下
↓
右上
↓
右下
```

---

# 16. PDF 表格切片

表格不要简单转成：

```text
张三 20 北京
李四 30 上海
```

推荐：

```text
姓名: 张三
年龄: 20
城市: 北京
```

或者转换为 Markdown Table。

大型表格推荐：

```text
Table
 ↓
Header
 ↓
Row Groups
```

例如：

```text
Chunk1 = Header + Row1~20
Chunk2 = Header + Row21~40
Chunk3 = Header + Row41~60
```

关键原则：

> 每个表格 Chunk 必须保留表头。

---

# 17. XLSX / Excel 切片

推荐模型：

```text
Workbook
 ↓
Sheet
 ↓
Table Region
 ↓
Header
 ↓
Row Group
```

示例：

```text
工作表：员工

姓名：张三
部门：技术
年龄：25

姓名：李四
部门：财务
年龄：30
```

Metadata：

```json
{
  "sheet": "员工",
  "rowStart": 2,
  "rowEnd": 30
}
```

大 Excel 不建议固定按行数切，而应：

```text
Token 上限
+
行完整性
+
Header 重复
```

综合控制。

---

# 18. 图片处理

对于 PDF、Word、PPT 中的图片，建议同时执行：

```text
Image
 ↓
OCR
 ↓
Vision LLM
 ↓
Image Embedding
```

产生三类信息：

## 18.1 OCR 文本

提取图片中的文字。

## 18.2 Vision Description

由视觉模型生成图片语义描述。

例如：

```text
该图描述支付系统架构：

客户端访问 API Gateway，
Gateway 转发到 Payment Service，
Payment Service 使用 MySQL 保存订单，
Redis 用于缓存。
```

用于文本 Embedding。

## 18.3 Image Embedding

通过多模态 Embedding 模型生成：

```text
image_vector
```

从而实现：

```text
文字搜图片
图片搜图片
图片搜相似架构图
```

---

# 19. 图片必须关联上下文

例如：

```text
图 3-2 系统架构图

[图片]

系统由 API Gateway...
```

不要生成完全孤立的 Image Chunk。

推荐：

```text
ImageChunk

caption
context_before
vision_description
ocr_text
context_after
```

Embedding 内容：

```text
图片标题
+
OCR
+
Vision Description
+
附近正文
```

---

# 20. OCR 扫描文档

扫描 PDF：

```text
Page
 ↓
Image
 ↓
OCR
 ↓
Layout Reconstruction
 ↓
Paragraph
 ↓
Chunk
```

OCR 应保留 Bounding Box：

```json
{
  "text": "支付流程",
  "x": 110,
  "y": 220,
  "width": 300,
  "height": 50
}
```

否则多栏、表格、复杂排版会被破坏。

---

# 21. Parent-Child Chunking

强烈推荐。

例如：

```text
Parent Chunk = 1200 tokens
Child Chunk = 350 tokens
```

结构：

```text
Parent A
├─ Child A1
├─ Child A2
├─ Child A3
└─ Child A4
```

检索：

```text
Query
 ↓
Child Vector Search
 ↓
命中 Child A2
 ↓
获取 Parent A
 ↓
LLM
```

优势：

```text
小 Chunk → 检索精准
大 Parent → 上下文完整
```

---

# 22. 推荐 Chunk 参数

## 普通知识文档

```yaml
parentChunkSize: 1200
childChunkSize: 350
childOverlap: 50
```

## 技术文档

```yaml
parentChunkSize: 1500
childChunkSize: 400
childOverlap: 80
```

## FAQ

```text
1 Question + 1 Answer = 1 Chunk
```

FAQ 不建议再次拆分。

---

# 23. Overlap 设计

Overlap 不是越大越好。

过大会增加：

```text
Embedding 成本
Milvus 容量
Elasticsearch 容量
重复召回
Rerank 成本
```

推荐：

```text
overlap ≈ chunk size 的 10%~20%
```

例如：

```text
400 tokens
overlap = 40~80 tokens
```

使用 Parent-Child 后可以适当降低 overlap。

---

# 24. Token 而不是 Character

不要使用：

```java
content.substring(0, 500);
```

切片应基于：

```text
Tokenizer
```

接口建议：

```java
public interface TokenCounter {

    int count(String text);

}
```

不同模型可以适配不同 Tokenizer：

```text
Qwen
OpenAI
BGE
其他 Embedding Model
```

---

# 25. Chunk Metadata

每个 Chunk 推荐至少包含：

```json
{
  "tenantId": "10001",
  "knowledgeBaseId": "20001",
  "documentId": "30001",

  "chunkId": "40001",
  "parentChunkId": "40000",

  "chunkIndex": 12,

  "documentName": "支付系统设计.pdf",

  "pageStart": 12,
  "pageEnd": 13,

  "sectionTitle": "退款流程",

  "sectionPath": [
    "支付系统",
    "退款",
    "退款流程"
  ],

  "contentType": "TEXT",

  "language": "zh-CN",

  "tokenCount": 387
}
```

支持 Citation：

```text
来源：
《支付系统设计.pdf》
第 12-13 页
退款 > 退款流程
```

---

# 26. 稳定 Chunk ID 与增量更新

建议计算：

```text
chunk_hash =
SHA256(
 document_id
 +
 section_path
 +
 normalized_content
)
```

目的：

```text
Dedup
Incremental Update
Change Detection
```

例如：

```text
100 个 Chunk
只有 3 个发生变化
```

则只需要：

```text
删除旧的 3 个 Vector
重新生成新的 3 个 Vector
```

不需要全文重新 Embedding。

---

# 27. Chunk 去重

常见重复文档：

```text
A.pdf
A_v2.pdf
A_final.pdf
A_final2.pdf
```

建议两层去重：

```text
Exact Dedup
Semantic Dedup
```

精准去重：

```text
SHA256(content)
```

近似去重：

```text
SimHash
MinHash
Embedding Similarity
```

例如：

```text
cosine similarity > 0.98
```

可以判定为高度重复。

---

# 28. Context Enrichment

原始 Chunk：

```text
退款时间为 7 个工作日。
```

语义不足。

建议增强为：

```text
文档：支付系统说明书
章节：退款 > 微信退款

退款时间为 7 个工作日。
```

推荐区分：

```text
content
embedding_content
```

其中：

```text
embedding_content =
Document Title
+
Section Path
+
Chunk Content
```

而引用展示仍然使用：

```text
content
```

---

# 29. Contextual Retrieval

进一步可以利用 LLM 给 Chunk 生成上下文前缀。

例如原文：

```text
退款时间为 7 个工作日。
```

生成：

```text
这段内容来自支付系统的微信退款章节，
主要描述微信退款到账时间。
```

最终 Embedding：

```text
上下文描述
+
原始 Chunk
```

优势：

- 对语义模糊 Chunk 提升明显
- Query 与原文措辞差异较大时效果更好

缺点：

- 入库成本增加
- LLM 调用增加

适合作为：

```text
HIGH_QUALITY
```

模式。

---

# 30. 推荐 Retrieval Pipeline

Chunking 必须与检索协同设计。

推荐：

```text
User Query
       ↓
Query Rewrite
       ↓
Query Expansion
       ↓
────────────────
↓              ↓
Milvus        Elasticsearch
Vector        BM25
↓              ↓
────── RRF Fusion ─────
          ↓
       Top 50
          ↓
     Qwen3-Rerank
          ↓
       Top 10
          ↓
Parent Expansion
          ↓
Dedup
          ↓
Context Assembly
          ↓
LLM
```

---

# 31. Neighbor Expansion

如果命中：

```text
Chunk 15
```

可以获取：

```text
Chunk 14
Chunk 15
Chunk 16
```

配置：

```yaml
neighbor:
  before: 1
  after: 1
```

适合：

- 说明书
- 连续流程
- 技术文档
- 法规条款

---

# 32. Parent Expansion

相比单纯 Neighbor Expansion，更推荐：

```text
Query
 ↓
Child Chunk
 ↓
Parent Chunk
```

例如命中：

```text
退款成功后……
```

Parent 可以包含完整：

```text
退款流程

1. 发起退款
2. 审核
3. 调用渠道
4. 接收回调
5. 更新状态
```

更适合送给 LLM。

---

# 33. Chunk Merge

如果召回：

```text
chunk 10
chunk 11
chunk 12
```

且：

```text
same document
same section
continuous index
```

可以合并：

```text
chunk 10~12
```

减少：

- 重复标题
- 重复 overlap
- 无效上下文占用

---

# 34. Chunk Diversity

TopK 不应全部来自同一区域。

可以引入：

```text
MMR
```

Maximum Marginal Relevance。

目标：

```text
相关性
+
多样性
```

避免：

```text
Top10 全部是几乎相同内容
```

---

# 35. 不同文件类型推荐策略

| 文件类型 | 推荐策略 |
|---|---|
| TXT | Recursive |
| Markdown | Heading + Recursive |
| HTML | DOM Structure |
| Word | Heading + Paragraph |
| PDF | Layout + Structure |
| Scan PDF | OCR + Layout |
| PPT | Slide + Block |
| XLSX | Sheet + Table |
| CSV | Header + Row Group |
| JSON | JSON Path |
| 图片 | OCR + Vision + Image Vector |
| FAQ | QA Pair |
| Code | AST / Function |

---

# 36. PPT 切片

PPT 的自然结构单位是 Slide。

推荐：

```text
Slide
 ↓
Title
 ↓
Text Blocks
 ↓
Table
 ↓
Image
 ↓
Recursive Split
```

Metadata：

```json
{
  "slide": 12,
  "title": "系统架构"
}
```

一页过长时再进行子切片。

---

# 37. JSON 切片

JSON 不要直接扁平化成无层级文本。

例如：

```json
{
  "payment": {
    "wechat": {
      "timeout": 30
    }
  }
}
```

建议保留：

```text
payment.wechat.timeout = 30
```

Chunk 可以基于：

```text
JSON Path
Object
Array
```

进行切分。

---

# 38. 代码文件切片

支持：

```text
.java
.py
.go
.ts
.js
```

时不建议使用普通 Token Split。

推荐：

```text
AST
```

例如 Java：

```text
Class
 ↓
Method
 ↓
Chunk
```

Chunk metadata：

```text
Class: PaymentService
Method: refund
Package: com.example.payment
```

可以提高代码知识库检索精度。

---

# 39. 切片质量评估

企业级系统不应该只凭感觉判断 Chunk 效果。

建议评估：

## Recall@K

正确 Chunk 是否出现在 TopK。

## MRR

正确结果平均排名。

## Context Precision

召回内容中真正相关信息比例。

## Context Recall

正确答案相关信息是否被完整召回。

## Faithfulness

最终回答是否严格基于检索上下文。

---

# 40. Chunk Size 应通过 Evaluation 确定

不要固定认为：

```text
512 tokens
```

就是最佳值。

应测试：

```text
256
384
512
768
1024
```

并比较：

```text
Recall@5
Recall@10
MRR
Context Precision
Answer Accuracy
Latency
Embedding Cost
```

最终根据真实数据集决定默认值。

---

# 41. 推荐的企业级整体 Pipeline

适用于：

```text
Java 21
Spring Boot
LangChain4j
Milvus
Elasticsearch
RabbitMQ
MinIO
Qwen Embedding
Qwen3-Rerank
```

推荐架构：

```text
                    Upload
                      │
                      ▼
                    MinIO
                      │
                      ▼
                Document Worker
                      │
                      ▼
               File Type Detect
                      │
                      ▼
                 Parser Router
       ┌──────────────┼──────────────┐
       ▼              ▼              ▼
     PDF            Office          Image
       │              │              │
       ▼              ▼              ▼
 PDFBox/Tika      POI/Tika       OCR/Vision
       │              │              │
       └──────────────┼──────────────┘
                      ▼
                Document AST
                      │
                      ▼
                Block Normalize
                      │
          ┌───────────┼────────────┐
          ▼           ▼            ▼
        Text        Table         Image
          │           │            │
          └───────────┼────────────┘
                      ▼
             Structure Chunker
                      │
                      ▼
              Parent Chunk
                      │
                      ▼
               Child Chunk
                      │
                      ▼
             Context Enrichment
                      │
                      ▼
                 Deduplicate
                      │
                      ▼
               Batch Embedding
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
       Milvus               Elasticsearch
       Vector                   BM25
          │                       │
          └───────────┬───────────┘
                      ▼
                  Hybrid
                      │
                     RRF
                      │
                      ▼
                 Top 50
                      │
                Qwen3-Rerank
                      │
                      ▼
                 Top 5~10
                      │
               Parent Expand
                      │
               Neighbor Merge
                      │
                      ▼
               Context Builder
                      │
                      ▼
                    LLM
```

---

# 42. 推荐 Chunk SPI 设计

Java 接口：

```java
public interface ChunkStrategy {

    boolean supports(DocumentContext document);

    List<Chunk> chunk(
        ParsedDocument document,
        ChunkConfig config
    );
}
```

实现：

```text
FixedChunkStrategy
RecursiveChunkStrategy
SemanticChunkStrategy
MarkdownChunkStrategy
PdfLayoutChunkStrategy
ExcelChunkStrategy
TableChunkStrategy
ParentChildChunkStrategy
ImageChunkStrategy
CodeChunkStrategy
```

路由器：

```java
ChunkStrategyRouter
```

根据：

```text
documentType
knowledgeBaseConfig
documentCharacteristics
```

动态选择。

---

# 43. 知识库配置建议

建议前端不要一次暴露几十个参数。

提供：

```text
快速
标准
高质量
自定义
```

自定义模式：

```yaml
chunkStrategy: AUTO

parentChunk:
  enabled: true
  size: 1200

childChunk:
  size: 400
  overlap: 60

structure:
  preserveHeading: true
  preserveTable: true
  preserveCodeBlock: true

pdf:
  removeHeaderFooter: true
  layoutAnalysis: true

image:
  ocr: true
  vision: true
  imageEmbedding: true

semantic:
  enabled: false
  threshold: 0.75

context:
  addDocumentTitle: true
  addSectionPath: true

dedup:
  exact: true
  semantic: true
  threshold: 0.98
```

---

# 44. 推荐默认策略

企业级知识库默认建议：

```text
AUTO STRUCTURE-AWARE PARENT-CHILD
```

推荐参数：

```text
Parent Chunk：1000~1500 tokens
Child Chunk：300~450 tokens
Overlap：40~80 tokens
```

Retrieval：

```text
Milvus Vector Top 50
+
Elasticsearch BM25 Top 50
+
RRF Fusion
+
Qwen3-Rerank Top 10
```

最终 Context：

```text
Top 5~8 Parent / Merged Chunks
```

---

# 45. 文件类型最终策略建议

```text
PDF
→ Layout + Heading + Table + Image/Vision + Parent-Child

Word
→ Heading + Paragraph + Parent-Child

Markdown
→ Heading Tree + Parent-Child

Excel
→ Sheet + Header + Row Group

PPT
→ Slide + Block

图片
→ OCR + Vision + Image Vector

HTML
→ DOM Tree

JSON
→ JSON Path

TXT
→ Recursive

Code
→ AST
```

---

# 46. 核心设计原则

企业级 Chunking 不应追求：

```text
每个 Chunk 一样大
```

而应该追求：

```text
语义完整
+
结构完整
+
检索精准
+
上下文完整
```

推荐优先级：

```text
Structure First
Token Second
Semantic Third
```

而不是：

```text
Token First
```

---

# 47. 建设优先级建议

建议优先完成：

```text
P0
结构化切片
Parent-Child
表格保护
Chunk Metadata
稳定 Chunk Hash
增量更新

P1
PDF Layout
OCR
Vision
Image Embedding
Hybrid Retrieval
RRF
Rerank

P2
Semantic Chunking
Contextual Retrieval
MMR
高级 Chunk Evaluation
```

Semantic Chunking 不一定是第一优先级。

更值得优先建设的是：

```text
结构化切片
+
Parent-Child
+
表格保护
+
图片/OCR/Vision
+
Metadata
+
Hybrid Retrieval
+
Rerank
```

---

# 48. 最终目标

不要把切片模块设计成单纯的：

```text
TextSplitter
```

而应该升级为完整：

```text
Document Intelligence Pipeline

Parser
  ↓
Layout
  ↓
Block
  ↓
Structure
  ↓
Chunk
  ↓
Enrich
  ↓
Embed
  ↓
Index
```

这套设计更加适合企业级、多租户、多文件类型、多模态 RAG 知识库系统。
