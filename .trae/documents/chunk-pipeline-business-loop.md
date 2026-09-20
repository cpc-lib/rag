# 优化 Chunk 过程，形成业务闭环

## Context

按 [RAG_DOCUMENT_PROCESSING_PIPELINE.md](file:///d:/release/rag/RAG_DOCUMENT_PROCESSING_PIPELINE.md) 对照当前实现，chunk → 检索 → LLM 链路存在 4 处缺口阻断业务闭环：

1. **§20 Qwen3-Rerank 缺失** — [RetrievalService.java#L91-L104](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/RetrievalService.java#L91-L104) RRF 后直接 topK 截断，无二阶段精排，噪声直达 LLM。
2. **§23 Token Budget 缺失** — [RetrievalService.java#L139-L162](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/RetrievalService.java#L139-L162) 全部 parent 内容拼入 Citation，无 max_context_tokens 控制，长文档/多引用易触发 LLM 上下文溢出。
3. **§14 语义边界优化只作独立模式** — [SemanticStrategy.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/SemanticStrategy.java) 仅作 `SEMANTIC` 模式入口，无法作为其他策略之后的 refinement 步骤（文档明确“更适合作为结构化切片后的优化步骤”）。
4. **多模态缺失** — [PdfLayoutStrategy.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/PdfLayoutStrategy.java) 只对扫描页做 OCR，PDF/DOCX 嵌入图片不走 Vision；[TableStrategy.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/TableStrategy.java) 只存 Markdown；ChunkEntity 无 `content_type` 列。

附加风险：V19 迁移源文件丢失（仅 `target/classes/db/migration/V19__chunk_content_type.sql` 存在），按项目记忆 V7 drift 教训需先排查再推进。

目标：在不动现有 15 种 chunk 策略签名的前提下，按 4 处缺口完成最小闭环。

## 关键设计决策

- **不改 `ChunkPlan` 6 参主构造器**，只新增可选字段 + 静态工厂方法 `table(...)` / `image(...)`，保证 15 个现有策略零改动。
- **Rerank 失败静默降级**（按项目记忆“rerank calls degrade silently”）：无 RERANK 模型或调用失败时，沿用 RRF 排序，不抛异常。
- **不新增 egress 检查**（当前代码无 egress gate，强行加入会扩大范围）。
- **图片不落 MinIO**（[MinioStorage.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/infrastructure/storage/MinioStorage.java) 仅有 download，扩展 upload 会改 chunk 表 schema 又增 MinIO client 依赖）；v1 直接把 Vision 描述作为 IMAGE chunk 的 content。
- **V19 drift 修复**：先校验 MySQL，按 V7 教训决定是恢复源文件、DELETE success=0 行、还是 DROP 孤儿列。

## 实施步骤

### 步骤 0：V19 drift 校验与恢复（前置）

执行 Shell（只读）检查 MySQL：
```
SELECT version, success, description FROM flyway_schema_history WHERE version='19';
SHOW COLUMNS FROM chunk LIKE 'content_type';
SELECT COUNT(*) FROM chunk WHERE content_type IS NOT NULL AND content_type <> 'TEXT';
```

按结果分支处理：
- **success=1 且列存在**：仅恢复 V19 源文件 `rag-api/src/main/resources/db/migration/V19__chunk_content_type.sql`，无 DB 动作。
- **success=0 且列存在**：`DELETE FROM flyway_schema_history WHERE version='19' AND success=0;` → 保留列（因 V19 仅加列、再加会报 Duplicate）→ 恢复源文件后启动，Flyway 跳过同 version 校验？**否，Flyway 会重跑 V19 报 Duplicate**。正确：`ALTER TABLE chunk DROP COLUMN content_type;` 后 `DELETE` 历史行 → 恢复源文件 → 启动重跑 V19。
- **success=0 且列不存在**：仅 `DELETE` 历史行，恢复源文件，重启重跑。
- **无记录且无列**：直接恢复源文件，启动跑 V19+V20。
- **无记录但有列（孤儿）**：先查非空数据；若为空则 `DROP COLUMN`，恢复源文件重启。

恢复的 V19 源文件内容直接 copy `target/classes/db/migration/V19__chunk_content_type.sql`。

### 步骤 1：V20 迁移 — 多模态列 + KB 语义细化开关

新建 [rag-api/src/main/resources/db/migration/V20__chunk_multimodal.sql](file:///d:/release/rag/rag-api/src/main/resources/db/migration/V20__chunk_multimodal.sql)：

```sql
ALTER TABLE `chunk`
    ADD COLUMN `table_json`     TEXT         NULL COMMENT '表格 JSON（contentType=TABLE 时）' AFTER `content_hash`,
    ADD COLUMN `table_summary`  TEXT         NULL COMMENT '表格自然语言摘要'                   AFTER `table_json`,
    ADD COLUMN `image_url`      VARCHAR(1024) NULL COMMENT '图片来源 URL'                       AFTER `table_summary`,
    ADD COLUMN `image_ocr`       TEXT         NULL COMMENT '图片 OCR 文本'                      AFTER `image_url`,
    ADD COLUMN `image_vision`   TEXT         NULL COMMENT '图片 Vision 描述'                    AFTER `image_ocr`;

ALTER TABLE `knowledge_base`
    ADD COLUMN `semantic_refine_enabled`     TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否在结构化切片后启用语义边界细化' AFTER `separators`,
    ADD COLUMN `semantic_similarity_threshold` DOUBLE NOT NULL DEFAULT 0.72
        COMMENT '相邻句向量余弦阈值，< 则判为主题边界' AFTER `semantic_refine_enabled`;
```

全部 nullable，规避 V7 类型错误。

### 步骤 2：扩展 ChunkEntity（API + Worker 两份）

文件：[rag-api/.../entity/ChunkEntity.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/persistence/entity/ChunkEntity.java) 和 [rag-worker/.../entity/ChunkEntity.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/infrastructure/persistence/entity/ChunkEntity.java)

新增字段（按 V20 列名 camelCase）：
```java
private String contentType;       // TEXT/TABLE/IMAGE/CODE
private String tableJson;
private String tableSummary;
private String imageUrl;
private String imageOcr;
private String imageVision;
```

### 步骤 3：扩展 KnowledgeBaseEntity（Worker 一份）

文件：[rag-worker/.../entity/KnowledgeBaseEntity.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/infrastructure/persistence/entity/KnowledgeBaseEntity.java)

新增：
```java
private Boolean semanticRefineEnabled;
private Double semanticSimilarityThreshold;
```

### 步骤 4：扩展 ChunkPlan 契约（不改主构造器）

文件：[rag-worker/.../pipeline/chunk/ChunkPlan.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/ChunkPlan.java)

新增可选字段 + 静态工厂，原 `independent` / `parentChild` 保留：
```java
public record ChunkPlan(boolean parentChild,
                        String sectionTitle, String sectionPath, String contentHash,
                        String parentContent, List<PlannedChild> children,
                        String contentType,      // null 视作 TEXT
                        String tableJson, String tableSummary,
                        String imageUrl, String imageOcr, String imageVision) {

    public static ChunkPlan independent(String title, String path, List<PlannedChild> children) {
        return new ChunkPlan(false, title, path, null, null, children, "TEXT", null, null, null, null, null);
    }
    public static ChunkPlan parentChild(String title, String path, String hash,
                                        String parentContent, List<PlannedChild> children) {
        return new ChunkPlan(true, title, path, hash, parentContent, children, "TEXT", null, null, null, null, null);
    }
    public static ChunkPlan table(String title, String path, PlannedChild child,
                                  String tableJson, String tableSummary) {
        return new ChunkPlan(false, title, path, null, null, List.of(child), "TABLE",
                tableJson, tableSummary, null, null, null);
    }
    public static ChunkPlan image(String title, String path, PlannedChild child,
                                  String imageUrl, String imageOcr, String imageVision) {
        return new ChunkPlan(false, title, path, null, null, List.of(child), "IMAGE",
                null, null, imageUrl, imageOcr, imageVision);
    }
}
```

### 步骤 5：表格三件套

文件：[rag-worker/.../pipeline/chunk/ChunkSupport.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/ChunkSupport.java)

新增两个工具方法（不改原方法签名）：

```java
/** Markdown 表格 → {"headers":[...],"rows":[[...]]} JSON。 */
public String tableToJson(String mdTable) { ... }

/** 简单摘要：列名 + 行数 + 首行采样，避免引入 LLM 调用。 */
public String tableToSummary(String mdTable) { ... }
```

文件：[TableStrategy.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/TableStrategy.java)

按 `Kind.TABLE` 块单独生成 `ChunkPlan.table(...)`，其余文本块沿用 `independent`。改写 `plan()` 内层逻辑，单一文件改动。

### 步骤 6：嵌入式图片 OCR + Vision（PDF 路径）

文件：[rag-worker/.../pipeline/chunk/PdfLayoutStrategy.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/PdfLayoutStrategy.java)

在 `plan(ChunkContext ctx)` 入参注入 `VisionClient` + `OcrService` + `ModelEntity vision`（构造器增加 3 个依赖）。

每页处理逻辑新增分支：
- 当 `text.length() < 500` 且 `visionClient.isConfigured(...)` 时：用 `PDFRenderer.renderImageWithDPI(page, 150)` 渲染整页 → 调 `visionClient.describe(...)` → 文本作为 `PlannedChild.text(page, visionText)` 加入 `pieces`，再走 `support.pack(...)`，最后 `ChunkPlan.independent(null, null, children, "IMAGE")` 输出。
- 无 Vision 配置时仍走 OCR 回退（已有逻辑）。

Vision 调用失败：catch + warn + skip（不抛异常，符合项目记忆 rerank 失败降级模式）。

> DOCX 嵌入图片暂不实现（POI `getEmbeddedPictures` 需进一步研究，超出“最小闭环”范围；DOCX 走 `STRUCTURE` 策略仍按 Markdown 表格三件套工作）。

### 步骤 7：PipelineProcessor 持久化多模态字段

文件：[rag-worker/.../pipeline/PipelineProcessor.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/PipelineProcessor.java#L187-L211) 的 `persistPlans`

按 `plan.contentType()` 写入对应字段：
- `IMAGE`：`c.setImageUrl(plan.imageUrl()); c.setImageOcr(plan.imageOcr()); c.setImageVision(plan.imageVision());`
- `TABLE`：`c.setTableJson(plan.tableJson()); c.setTableSummary(plan.tableSummary());`
- 默认 `TEXT` / `CODE`：仅 content
- 总是 `c.setContentType(plan.contentType() == null ? "TEXT" : plan.contentType())`

### 步骤 8：语义边界优化作为 post-step

新建 [rag-worker/.../pipeline/chunk/SemanticRefiner.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/chunk/SemanticRefiner.java) `@Component`：

```java
public List<ChunkPlan> refine(List<ChunkPlan> plans, ChunkContext ctx, double threshold);
```

复用 `SemanticStrategy` 内部句向量相邻相似度逻辑：对每个 `ChunkPlan.parentChild=false` 且 children>1 的 plan，按句切分 → embedding → 相邻 cosine < threshold 处断 → 重新 `support.pack(..., size, 0)`。

依赖：`ChunkSupport`、`EmbeddingClient`、`ModelMapper`（与 SemanticStrategy 相同，可考虑提取共享 `SemanticSupport`，但首版接受 30 行重复以避免改动 SemanticStrategy 现有契约）。

文件：[PipelineProcessor.java](file:///d:/release/rag/rag-worker/src/main/java/com/rag/worker/pipeline/PipelineProcessor.java#L94-L100) `parseFlow`

`strategy.plan(chunkCtx)` 之后插入：
```java
if (Boolean.TRUE.equals(kb.getSemanticRefineEnabled())) {
    double thr = kb.getSemanticSimilarityThreshold() == null ? 0.72 : kb.getSemanticSimilarityThreshold();
    plans = semanticRefiner.refine(plans, chunkCtx, thr);
}
```

构造器注入 `SemanticRefiner`。

### 步骤 9：RERANK 模型类型

文件：[rag-api/.../application/ModelService.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/ModelService.java#L25-L29)

```java
public static final String RERANK = "RERANK";
private static final Set<String> TYPES = Set.of(CHAT, VISION, EMBEDDING, IMAGE, RERANK);
// label() 增 case RERANK -> "重排";
```

[ModelEntity](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/persistence/entity/ModelEntity.java) 表结构与 CHAT 类似（base_url/api_key/model），无需 DDL。

### 步骤 10：RerankClient（OpenAI 兼容）

新建 [rag-api/.../infrastructure/llm/RerankClient.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/llm/RerankClient.java) `@Component`：

参考 [LlmClient.java#L149-L187](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/llm/LlmClient.java#L149-L187) 的 WebClient + newConnection 模式。

```java
public record RerankHit(int index, double score) {}

public List<RerankHit> rerank(String baseUrl, String apiKey, String model,
                              String query, List<String> documents, int topN) {
    // POST {base}/rerank  body {model, query, documents, top_n}
    // 解析 results[].index + results[].relevance_score
    // 失败：log.warn + return List.of()（不抛）
}
```

> DashScope `gte-rerank` 走 `/services/rerank/rerank`，与 SiliconFlow `/rerank` 路径不同。首版默认按 SiliconFlow 兼容协议；DashScope 走 compatible-mode/v1 不支持 rerank，按项目记忆“Aliyun MAAS 不支持 rerank，用 DashScope 主端点 https://dashscope.aliyuncs.com”。

### 步骤 11：扩展 EtcdService RetrievalConfig

文件：[rag-api/.../infrastructure/etcd/EtcdService.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/etcd/EtcdService.java#L33-L39)

```java
public static class RetrievalConfig {
    public int vectorTopN = 20;
    public int keywordTopN = 20;
    public int topK = 8;        // RRF 后送入 rerank 的候选数
    public int rerankTopN = 5;   // rerank 后保留数（新）
    public int maxContextTokens = 12000;  // 新
    public int rrfK = 60;
    public double lowConfidenceThreshold = 0.35;
}
```

构造器 `@Value` 默认值 + `applyRetrieval()` 解析新字段 + `application.yml` 增加 `rag.retrieval.rerank-top-n`、`rag.retrieval.max-context-tokens`。

### 步骤 12：RetrievalService 接入 Rerank + Token Budget

文件：[rag-api/.../application/RetrievalService.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/RetrievalService.java)

构造器注入 `RerankClient` + `RerankModelService`（即复用 `ModelService.findEnabled(tenantId, "RERANK")`，不另起 service）。

`retrieve()` 在 RRF topK（[L100-L104](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/RetrievalService.java#L100-L104)）之后、parent 扩展之前：
1. 查 RERANK 模型；若存在且 `RerankClient` 配置完整，取 topK 候选 content 调 `rerankClient.rerank(...)`，按 score 重排取前 `rerankTopN`。
2. 失败：log.warn + 沿用 RRF 排序取前 `rerankTopN`。
3. parent 扩展 + 去重保持不变。
4. 在最后构建 `List<Citation>` 时（[L139-L162](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/RetrievalService.java#L139-L162)），按 `maxContextTokens` 累加 `unit.getContent()` 的 token 数（用近似 counter：中文 1 token、ASCII run ceil(len/4)），超预算则 break，剩余 citation 不发送。

新增 `Citation` 字段：`rerankScore`（可空），便于前端展示排序依据。`buildMaterial` 在 [ChatOrchestrator.java#L298-L309](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/application/ChatOrchestrator.java#L298-L309) 不需改。

### 步骤 13：API 端 Token Counter（轻量）

新建 [rag-api/.../infrastructure/util/ApproxTokenCounter.java](file:///d:/release/rag/rag-api/src/main/java/com/rag/api/infrastructure/util/ApproxTokenCounter.java)：

```java
public static int count(String text) {
    // 与 worker TokenCounter 一致口径：中文字符=1，ASCII run ceil(len/4)，其他非空白=1
}
```

`RetrievalService` 内部使用静态方法即可。

## 验证方案

1. **DDL**：
   ```sql
   SHOW COLUMNS FROM chunk;  -- 应含 content_type/table_json/table_summary/image_url/image_ocr/image_vision
   SHOW COLUMNS FROM knowledge_base;  -- 应含 semantic_refine_enabled/semantic_similarity_threshold
   SELECT version, success FROM flyway_schema_history WHERE version IN ('19','20');
   ```

2. **Worker 启动**：在 IDE Rerun rag-worker，看日志 `Flyway V19/V20 已应用`，无 Duplicate column 报错。

3. **切片回归**：上传一份 PDF（含表格 + 嵌入图片），观察 `chunk` 表应出现 `content_type='TABLE'` 行带 `table_json/table_summary`，`content_type='IMAGE'` 行带 `image_vision`。日志：`文档解析完成 doc={} strategy=PDF_LAYOUT ... children=N`。

4. **语义细化**：在 KB 配置打开 `semantic_refine_enabled=1`，上传长 Markdown，对比 children 数量应不少于未开时，且相邻片主题应更纯。

5. **Rerank + Token Budget**：
   - 在租户启用 RERANK 模型（SiliconFlow `bge-reranker-v2-m3` 或 Jina `jina-reranker-v2`）。
   - 调用 `/api/v1/chat/sessions/{kbId}/stream` 提问，SSE `search_result` 事件中 citations 数量 ≤ `rerankTopN`，且总 content tokens ≤ `maxContextTokens`。
   - 临时关闭 RERANK 模型，日志应出现 `rerank 调用失败，沿用 RRF 排序`，问答仍正常完成。

6. **编译验证**：
   ```
   mvn -pl rag-api,rag-worker -am clean test -DskipITs
   ```

## 不实施 / 显式排除

- DOCX 嵌入图片提取（POI XWPFPictureData 路径复杂，超出最小闭环）。
- 图片落 MinIO（需新增 upload 方法 + 引用 URL 列；当前 image_vision 文本已足够召回）。
- egress gate（项目代码中不存在，强行加入会扩大改动面）。
- Flyway repair 脚本自动执行（属 DBA 操作，由用户在 MySQL 客户端手动执行）。
- 前端 ModelConfigPage / KB 配置 UI 改动（前端独立工程，本轮仅打通后端契约）。
