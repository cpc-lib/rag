# OPTIMIZE P2 规格说明（Spec）

> 与 [PLAN.md](./PLAN.md) 配套；实现顺序见 [tasks.md](./tasks.md)。
> 所有路径基于仓库根目录 `d:\release\rag`。

---

## 1. 索引一致性对账与孤儿清理

### 1.1 术语

- **期望集合（expected）**：MySQL 中某文档的有效 CHILD 切片 id，条件 `deleted = 0 AND chunk_type = 'CHILD' AND status != 'DELETED'`。
- **索引集合（actual）**：ES 索引 / Milvus 集合中 `documentId = X`（Milvus 为 `document_id == X`）的全部 id。
- **孤儿（orphan）**：`actual - expected`，即索引里存在、MySQL 已无对应有效切片。
- **缺失（missing）**：`expected - actual`，即 MySQL 有效但索引未写入。

### 1.2 组件与职责

| 组件 | 模块 | 职责 |
|---|---|---|
| `IndexReconcileService`（新） | rag-worker | 调度、预算、游标、比对、删除孤儿、排空补偿任务、写报告 |
| `ReconcileBudget`（新，内部类/独立类） | rag-worker | 外部调用计数、截止时间、超限中断 |
| `EsIndexer#listChunkIds`（新方法） | rag-worker | 按文档分页列出 `chunkId` |
| `MilvusIndexer#listChunkIds`（新方法） | rag-worker | 按文档列出 `chunk_id`（带上限） |
| `IndexCleanupTask`（新表/实体/Mapper） | rag-common | 记录索引删除失败的补偿任务 |
| `PipelineOpsService#lastReconcileReport`（新方法） | rag-api | 读取 Redis 报告 |
| `PipelineOpsController`（新增 1 个 GET） | rag-api | 平台管理员查看最近报告 |

### 1.3 数据模型

**新增迁移** `rag-api/src/main/resources/db/migration/V28__index_cleanup_task.sql`（现有迁移已到 V27，按序递增）

```sql
CREATE TABLE IF NOT EXISTS index_cleanup_task (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(32)  NOT NULL,
  kb_id       BIGINT       NOT NULL,
  document_id BIGINT       NOT NULL,
  target      VARCHAR(16)  NOT NULL COMMENT 'ES / MILVUS / BOTH',
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE/FAILED',
  attempts    INT          NOT NULL DEFAULT 0,
  error_msg   VARCHAR(512) DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_status_created (status, created_at),
  KEY idx_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**实体** `rag-common/src/main/java/com/rag/api/infrastructure/persistence/entity/IndexCleanupTaskEntity.java`
- `@Data @TableName("index_cleanup_task")`，字段与 DDL 一一对应（`id/tenantId/kbId/documentId/target/status/attempts/errorMsg/createdAt/updatedAt/deleted`），与现有实体风格一致，**不加** `@TableLogic`。

**Mapper** `IndexCleanupTaskMapper.java` + `resources/mapper/IndexCleanupTaskMapper.xml`

| 方法 | SQL 语义 |
|---|---|
| `int insert(IndexCleanupTaskEntity)` | `useGeneratedKeys`，insert 列与实体一致 |
| `List<IndexCleanupTaskEntity> selectPending(@Param("limit") int)` | `WHERE deleted=0 AND status='PENDING' ORDER BY id LIMIT #{limit}` |
| `int markDone(@Param("id") Long)` | `UPDATE ... SET status='DONE', error_msg=NULL WHERE deleted=0 AND id=#{id}` |
| `int markFailed(@Param("id") Long, @Param("errorMsg") String)` | `SET status = IF(attempts + 1 >= 3, 'FAILED', 'PENDING'), attempts = attempts + 1, error_msg=#{errorMsg}` |
| `int logicDeleteByDocumentId(@Param("documentId") Long)` | `UPDATE ... SET deleted=1 WHERE deleted=0 AND document_id=#{documentId}` |
| `int logicDeleteByKbId(@Param("kbId") Long)` | 同上按 kb |
| `int purgeFinishedBefore(@Param("days") int)` | `UPDATE ... SET deleted=1 WHERE deleted=0 AND status IN ('DONE','FAILED') AND updated_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)` |

### 1.4 查询能力补充

| Mapper | 新方法 | XML |
|---|---|---|
| `KnowledgeBaseMapper` | `List<KnowledgeBaseEntity> selectAllAfterId(@Param("afterId") Long, @Param("limit") int)` | `WHERE deleted=0 AND id > #{afterId} AND es_index IS NOT NULL AND milvus_collection IS NOT NULL ORDER BY id LIMIT #{limit}` |
| `DocumentMapper` | `List<Long> selectIdsByKbIdAfterId(@Param("kbId") Long, @Param("afterId") Long, @Param("limit") int)` | `WHERE deleted=0 AND kb_id=#{kbId} AND id > #{afterId} ORDER BY id LIMIT #{limit}` |
| `ChunkMapper` | `List<Long> selectChildIdsByDocumentId(@Param("documentId") Long)` | `SELECT id FROM chunk WHERE deleted=0 AND document_id=#{documentId} AND chunk_type='CHILD' AND status!='DELETED' ORDER BY id` |

> 不复用 `selectChildrenByDocumentId`：该查询返回完整 `content`，对账只需 id，避免大字段传输与内存放大。

### 1.5 索引读取

**`EsIndexer#listChunkIds(String index, long documentId, int cap)`**

- 返回 `record ChunkIdPage(List<Long> ids, boolean truncated)`。
- 查询：`term(documentId)`，`_source` 仅 `chunkId`，按 `chunkId` 升序，每页 `size = min(500, cap)`，用 `search_after` 翻页，直到无更多或 `ids.size() >= cap`。
- `chunkId` 在 mapping 中为 `keyword`，排序与 `search_after` 使用字符串值。
- 索引不存在（`hasIndex=false`）返回空页且 `truncated=false`，由上层记为 `missingIndex`，**不抛错中断整轮**。
- 单页异常向上抛出，由上层计入 `errors` 并中止当前 KB（不删除任何数据）。

**`MilvusIndexer#listChunkIds(String collection, long documentId, int cap)`**

- 返回 `record ChunkIdPage(List<Long> ids, boolean truncated)`。
- 集合不存在返回空页并标记 `missingIndex`。
- 查询：`filter("document_id == " + documentId)`，`outputFields(List.of("chunk_id"))`，`limit = min(cap, 16384)`；返回行数 `== limit` 视为 `truncated=true`（无法确认完整集合）。
- `truncated=true` 的文档**禁止删除**，只计入报告 `truncatedDocs`，避免误删。

### 1.6 服务算法（IndexReconcileService）

调度：
```java
@Scheduled(fixedDelayString = "${rag.reconcile.interval-ms:3600000}",
           initialDelayString = "${rag.reconcile.initial-delay-ms:300000}")
```
入口先判断 `rag.reconcile.enabled`；用 Redis `SET rag:reconcile:lock <runId> NX EX <maxDurationSeconds>` 防多 Worker 并发，未抢到直接返回。

执行步骤：

1. **排空补偿任务**（独立小预算）
   - `selectPending(cleanupTaskLimit)`。
   - 对每条：`documentMapper.selectById(documentId)`。
     - 文档仍存在 → `markDone` 并记录“跳过：文档未删除”（避免删活数据）。
     - 文档不存在 → 按 `target` 调用 `esIndexer.deleteByDocument` / `milvusIndexer.deleteByDocument`；成功 `markDone`，失败 `markFailed(error)`（最多 3 次）。
   - 每处理一条计 1~2 次外部调用。
2. **选择知识库**
   - 读 Redis `rag:reconcile:cursor`（默认 0）→ `selectAllAfterId(cursor, 1)`；无结果则回绕到 `selectAllAfterId(0, maxKbsPerRun)`。
   - 跳过 `indexState = "BUILDING"` 的 KB（记录 `skipped`，游标继续）。
   - 读取版本：`v = kb.indexVersion == null ? 0 : kb.indexVersion`；ES 名 = `IndexNames.physical(kb.esIndex, v)`，Milvus 名 = `IndexNames.physical(kb.milvusCollection, v)`。
   - 记录 `truncatedDocs` / `errors`；报告写 Redis。
3. **遍历文档**（`maxDocsPerKb`）
   - 文档游标存 `rag:reconcile:doc:{kbId}`（TTL 24h），`selectIdsByKbIdAfterId(kbId, cursor, pageSize)`；页大小 = `min(50, 剩余文档预算)`。
   - 逐文档：
     - `expected = chunkMapper.selectChildIdsByDocumentId(docId)`。
     - `es = esIndexer.listChunkIds(esName, docId, maxIdsPerDoc)`；`milvus = milvusIndexer.listChunkIds(milvusName, docId, maxIdsPerDoc)`。
     - 任一 `truncated` → 记 `truncatedDocs`，跳过该文档删除。
     - 计算 `orphanEs/orphanMilvus/missingEs/missingMilvus`（`HashSet` 差集，long）。
     - `dryRun=false` 时：
       - `expected` 非空 → `esIndexer.deleteStale(esName, docId, expected)` / `milvusIndexer.deleteStale(milvusName, docId, expected)`（既有方法，幂等）。
       - `expected` 为空 → `deleteByDocument`（文档无有效切片，索引应清空）。
       - `missing` 不修复。
     - 每文档至少 2 次外部调用；删除另计。
   - 每处理完一页更新文档游标；KB 全部完成删除游标键并推进 KB 游标。
4. **收尾**
   - 每轮执行一次 `purgeFinishedBefore(retentionDays)`（无外部调用）。
   - 报告 JSON 写 Redis `rag:reconcile:last`，TTL `reportTtlHours`；同时 `log.info` 输出关键计数。
   - 释放锁（Lua 比较 runId 后 DEL，避免删他人锁）。

**安全约束（硬性）**
- MySQL 读取异常 → 当前 KB 立即中止，不产生任何删除。
- 索引读取异常 → 当前 KB 中止。
- 预算耗尽 → 完成当前文档后停止，报告 `budgetExhausted=true`，游标保留以便续跑。
- `dryRun=true` 时只读，绝不调用删除。

### 1.7 配置（`rag-worker/src/main/resources/application.yml`）

```yaml
rag:
  reconcile:
    enabled: ${RAG_RECONCILE_ENABLED:false}
    dry-run: ${RAG_RECONCILE_DRY_RUN:true}
    interval-ms: ${RAG_RECONCILE_INTERVAL_MS:3600000}
    initial-delay-ms: ${RAG_RECONCILE_INITIAL_DELAY_MS:300000}
    max-kbs-per-run: ${RAG_RECONCILE_MAX_KBS:1}
    max-docs-per-kb: ${RAG_RECONCILE_MAX_DOCS:200}
    max-ids-per-doc: ${RAG_RECONCILE_MAX_IDS:10000}
    max-external-calls: ${RAG_RECONCILE_MAX_CALLS:2000}
    max-duration-ms: ${RAG_RECONCILE_MAX_DURATION_MS:300000}
    cleanup-task-limit: ${RAG_RECONCILE_CLEANUP_LIMIT:100}
    delete-kb-ids: ${RAG_RECONCILE_DELETE_KB_IDS:}   # dry-run=false 时的删除白名单，空=不限
    retention-days: ${RAG_RECONCILE_RETENTION_DAYS:30}
    report-ttl-hours: ${RAG_RECONCILE_REPORT_TTL_HOURS:168}
```

**外部调用预算定义**：ES 的 `hasIndex` / 每页 `search` / `deleteByQuery`，Milvus 的 `hasCollection` / `query` / `delete`，每次计 1。启动时校验 `max-docs-per-kb * 2 <= max-external-calls`，不满足则 `log.warn` 并自动下调 `max-docs-per-kb`（不阻断启动）。

### 1.8 报告与查询接口

**Redis 键**：`rag:reconcile:last`，值为 JSON：

```json
{
  "runId": "uuid",
  "startedAt": "2026-11-23T10:00:00+08:00",
  "finishedAt": "2026-11-23T10:02:11+08:00",
  "dryRun": true,
  "kbId": 12,
  "tenantId": "t_xxx",
  "indexVersion": 3,
  "documentsScanned": 180,
  "documentsSkipped": 2,
  "orphanEs": 12,
  "orphanMilvus": 3,
  "missingEs": 5,
  "missingMilvus": 0,
  "deletedEs": 0,
  "deletedMilvus": 0,
  "cleanupTasksDrained": 4,
  "cleanupTasksFailed": 0,
  "externalCalls": 401,
  "budgetExhausted": false,
  "truncatedDocs": [456],
  "errors": ["doc=123: ES search timeout"],
  "skippedKbs": []
}
```

- 不含正文，`errors` 最多 20 条，`truncatedDocs` 最多 20 个 id。
- `PipelineOpsService#lastReconcileReport()` 读取该键，缺失返回 `Map.of("available", false)`；Redis 异常时抛 `BizException(UPSTREAM)` 由全局处理器统一响应。
- `PipelineOpsController` 新增：`GET /api/v1/ops/pipeline/reconcile/last`，`AuthGuard.requirePlatform()`。

### 1.9 删除链路改造

`rag-api/.../DocumentAppService#delete(long documentId)`（现 395-418 行）：

- ES 删除：保持现状（失败抛出，删除不提交）。
- Milvus 删除：`catch` 分支由“仅 warn”改为：
  ```java
  cleanupTaskMapper.insert(new IndexCleanupTaskEntity(
      doc.getTenantId(), doc.getKbId(), doc.getId(), "MILVUS", "PENDING", 0, e.getMessage()));
  ```
  并保留 warn 日志。
- 其余步骤（MinIO、chunk/task/document 逻辑删除、文件库清理）不变。
- 新增构造依赖 `IndexCleanupTaskMapper`。

### 1.10 测试

`rag-worker/src/test/java/com/rag/worker/reconcile/IndexReconcileServiceTest.java`（Mockito，不依赖中间件）：

1. `dryRun=true`：孤儿计数正确，`deleteStale`/`deleteByDocument` 从未被调用。
2. `dryRun=false`：`expected` 非空时调用 `deleteStale(..., expected)`；`expected` 为空时调用 `deleteByDocument`。
3. `truncated=true`：不删除，计入 `truncatedDocs`。
4. MySQL 查询抛异常：当前 KB 中止，`errors` 非空，无删除。
5. 预算耗尽：在文档边界停止，`budgetExhausted=true`，文档游标已推进。
6. `indexState=BUILDING` 跳过。
7. 补偿任务：文档仍存在 → `markDone` 且无外部删除；文档不存在 → 调删除并 `markDone`；删除失败 → `markFailed`。

手工集成验证（本地 compose）：
1. `enabled=false` 启动 Worker，观察无 ES/Milvus 请求日志。
2. 造 1 条 SQL 有效切片 + 1 条孤立 ES 文档 + 1 条孤立 Milvus 向量。
3. `enabled=true, dry-run=true`：报告 `orphanEs=1, orphanMilvus=1, deleted*=0`。
4. `dry-run=false`：孤儿被删，`chunk` 表行数不变；配置 `delete-kb-ids` 时仅白名单 KB 执行删除。
5. 删除文档时人为让 Milvus 不可达：`index_cleanup_task` 新增 PENDING 行；恢复 Milvus 后下一轮巡检置 DONE。

### 1.11 可观测性

- 每轮 `INFO`：`runId/kbId/docs/calls/duration/orphans/deleted/budgetExhausted`。
- 删除单条失败 `WARN`；KB 中止 `WARN`；配置被自动下调 `WARN`。
- 不打印切片正文、不打印 API Key。

### 1.12 回滚

- 关闭 `rag.reconcile.enabled` 即停止巡检；补偿表仅追加，不影响主链路。
- 代码回滚：删除新增服务/接口/配置与 V2 脚本；已建表可保留（无外键、无程序读取）。

---

## 2. 前端超大单文件组件拆分

### 2.1 现状职责映射（`knowledge-console/src/pages/FileLibraryPage.tsx`，1309 行）

| 行区间（约） | 职责 | 去向 |
|---|---|---|
| 1-68 | 常量与格式化函数（`previewKind`/`formatSize`/`formatDuration`） | 页面保留（`previewKind` 可导出给弹窗复用） |
| 69-100 | 页面状态（文件、上传、预览、播放、转码、历史） | 按归属拆分 |
| 101-155 | WebSocket 转码进度、订阅 | 页面保留（单连接） |
| 156-225 | `load`/`loadHistory`/防抖搜索/`saveProgress` | `load/loadHistory`→页面；`saveProgress`→播放器 |
| 226-380 | 快捷键、seek/volume、进度保存、图片内容加载 | 播放器/预览弹窗 |
| 382-446 | HLS 初始化与恢复进度 | `VideoPlayerModal` |
| 448-456 | 下载（`?token=`） | 页面 |
| 458-548 | SHA-256、断点续传、分片上传、秒传、批内去重 | `useMultipartUpload` |
| 550-613 | 删除、关闭弹窗、播放控制、全屏、窗口缩放 | 删除→页面；其余→播放器 |
| 618-683 | Card 头部：搜索、刷新、上传、进度 Popover | 页面 |
| 685-1000 | 文件表格、业务类型/大小/时间、行操作（查看/转码/停止/删除/下载） | 页面 |
| 1000-1190 | 预览 Modal：图片/Markdown/文本/音频/视频/字幕 Tab | 按类型拆到两个弹窗 |
| 1195-1309 | `CtrlBtn`/`SkipIcon`/`SeekBar` | 随播放器文件 |

### 2.2 目标结构

```
src/
├─ hooks/useMultipartUpload.ts                 （新，约 130 行）
├─ components/library/VideoPlayerModal.tsx     （新，约 550 行）
├─ components/library/DocumentPreviewModal.tsx （新，约 260 行）
└─ pages/FileLibraryPage.tsx                   （约 300 行）
```

**`useMultipartUpload` 契约**

```ts
export interface UploadTask { key: string; name: string; percent: number; tip?: string; speed?: string }

export function useMultipartUpload(opts: { onCompleted: () => void }): {
  uploads: UploadTask[];
  beforeUpload: (file: File) => Promise<boolean>;
};
```
- 内部持有 `seenSha256Ref`，任务结束移除（保持现有“删后可重传同文件”语义）。
- `localStorage` key 不变：`upload-session:{name}:{size}`。
- 秒传命中与成功完成均调用 `onCompleted()`（页面传入 `() => load(kw)`）。
- 依赖 `uploadApi`、`hash-wasm`、`App.useApp().message`。

**`VideoPlayerModal` 契约**

```ts
interface Props {
  file: LibraryFile | null;
  open: boolean;
  token: string | null;
  progressEvent: { fileId: number; status: string; progress: number } | null;
  onClose: () => void;
  onTranscoded: () => void; // 转码 READY 后刷新列表
}
```
- 拥有：`mediaRef/hlsRef`、`playReady/playHls/levels/quality`、`paused/curTime/duration/bufEnd/volume/muted`、`resumeSec`、`isFullscreen`、`modalSize`、快捷键监听。
- `playback` 查询（`libraryApi.playback`）与关闭保存（`savePlaybackPosition`）在弹窗内完成。
- 不新建 WebSocket；转码进度由页面通过 `progressEvent` 注入，弹窗内 `useEffect` 监听并更新 `playReady/transcodePct`。
- 保持现有 DOM 结构与内联样式，不改视觉。

**`DocumentPreviewModal` 契约**

```ts
interface Props {
  file: LibraryFile | null;
  open: boolean;
  onClose: () => void;
  onEditSubtitle: (f: LibraryFile) => void;
}
```
- 负责图片（`imageUrl`）、Markdown（`MarkdownViewer`）、文本、音频预览与字幕 Tab 的展示。
- 视频类型不进入该组件（由页面路由到 `VideoPlayerModal`）。

**页面保留**
- 文件列表/搜索/分页/Tab（全部文件、播放记录）。
- 上传按钮与进度 Popover。
- 行操作：查看（按 kind 打开对应弹窗）、转码/停止、删除、下载。
- WebSocket 单连接与 `transcodeJobs` 状态；`SubtitleEditDrawer` 的 `editing` 状态。

### 2.3 行为契约（必须保持不变）

1. 关闭播放弹窗保存一次进度；`positionMs < 1000` 不保存。
2. 视频优先 HLS（`playbackStatus=READY`），否则直连 `/stream`；`?token=` 与 `xhrSetup` 注入 `Authorization` 不变。
3. 清晰度切换、全屏、窗口缩放、快捷键（空格/K/←/→/↑/↓/M）行为一致。
4. 上传：SHA-256 流式分块、秒传、断点续传、批内去重、失败提示文案一致。
5. WS 订阅逻辑：列表刷新时对 `PROCESSING` 文件自动订阅；转码完成自动切 HLS。
6. 不修改 Antd 结构、className、文案、颜色与间距。

### 2.4 风险与收益评估

**收益**
- 页面文件 1309 → 约 300 行，职责边界清晰；播放器与上传可独立修改/测试。
- 减少大文件 diff 与合并冲突；后续 HLS 调优、上传协议调整互不影响。
- 为将来给播放器补单测（纯函数/状态机）留出空间。

**风险**
- 播放器状态高度耦合（HLS ref、恢复进度、全屏、缩放、WS 进度），迁移易产生时序回归。
- 上传断点续传/秒传/去重依赖 ref 与 localStorage，抽 Hook 时易漏清理。
- 预览弹窗与字幕抽屉共享 `editing` 状态，边界划错会导致重复弹窗。

**缓解**
- 纯搬移，不改逻辑；保持变量名与 effect 依赖数组。
- 两个提交：先上传 Hook，再播放/预览弹窗；每个提交独立 typecheck/build + 手工清单。
- 对照本文 2.1 行区间逐块迁移，迁移后删除孤儿 import。

### 2.5 验收

```bash
cd knowledge-console
npm run typecheck
npm run build
```

手工清单：列表/搜索/分页；多文件上传（断点续传、秒传、同内容跳过）；图片/Markdown/文本/音频预览；视频 HLS 播放、清晰度、全屏、缩放、恢复进度、关闭保存；转码进度实时更新与完成后刷新；字幕编辑与下载；删除文件。

构建产物：`FileLibraryPage` 懒加载 chunk 仍独立，主包体积不增加。

### 2.6 回滚

按提交回滚；两个提交分别对应“上传 Hook”和“播放/预览弹窗”，互不依赖。

---

## 3. 音视频预览走流式：现状结论（不新增实现）

### 3.1 证据

1. 文档上传白名单不含音视频：`ALLOWED_EXT = {txt, md, pdf, doc, docx, xlsx, html, htm, png, jpg, jpeg}`（[DocumentAppService.java:51](../../rag-api/src/main/java/com/rag/api/application/DocumentAppService.java#L51)）。因此 `/api/v1/documents/{id}/preview` 的 `video/audio` 分支在当前产品约束下不可达。
2. 文件库已提供完整流式能力：
   - `GET /api/v1/library/files/{id}/stream` 支持 `Range`、`206`、`Content-Range`、`Accept-Ranges`，并按 `playbackKey` 播放转码产物（[FileLibraryController.java:145-194](../../rag-api/src/main/java/com/rag/api/interfaces/FileLibraryController.java#L145-L194)）。
   - `GET /{id}/download` 与 `/{id}/hls/**` 均为流式输出，不落内存。
   - `JwtAuthFilter` 白名单允许这些路由用 `?token=` 鉴权（[JwtAuthFilter.java:42](../../rag-api/src/main/java/com/rag/api/interfaces/security/JwtAuthFilter.java#L42)）。
   - 前端文件库播放器直接使用 `stream?token=` 与 HLS（`FileLibraryPage.tsx` 448-456、382-433）。

### 3.2 结论

- 本项**不新增后端或前端实现**，避免与文件库既有能力重复。
- `FilePreviewPage.tsx` 的 `video/audio` 分支属于历史代码，按仓库约定“历史死代码只提示不删除”，本次保留。

### 3.3 残余缺口与触发条件

- `/api/v1/documents/{id}/preview` 不支持 `Range`，且未加入 `?token=` 白名单。仅当未来允许音视频作为“知识库文档”上传时，才需要：
  1. 为 `DocumentController#preview` 增加 Range 处理；
  2. 把 `/api/v1/documents/\d+/preview` 加入 `JwtAuthFilter` 白名单；
  3. `FilePreviewPage` 音视频改用直链而非 Blob。
- 当前不满足触发条件，故不在本次范围。

### 3.4 验收

- 文档结论与代码引用一致；本项无代码变更、无构建验证要求。

---

## 4. 执行与审批

1. 本 spec 与 PLAN、tasks 一并提交评审。
2. 评审通过后按 [tasks.md](./tasks.md) 顺序执行；每完成一个阶段运行对应验证命令。
3. 对账默认 `enabled=false / dry-run=true` 上线，观察一轮报告后再按需开启删除。
