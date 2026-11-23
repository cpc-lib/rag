# OPTIMIZE P2 任务清单（tasks）

> 规格见 [spec.md](./spec.md)，计划见 [PLAN.md](./PLAN.md)。
> 状态：`[ ]` 待办 / `[~]` 进行中 / `[x]` 完成。**评审通过前不执行。**

## 阶段 A：索引一致性对账与孤儿清理

- [x] A1 新增迁移 `rag-api/src/main/resources/db/migration/V28__index_cleanup_task.sql`
  - 建 `index_cleanup_task` 表（字段/索引见 spec 1.3）
  - 验证：空库启动 rag-api，Flyway 执行成功；`SHOW CREATE TABLE` 字段一致
- [x] A2 新增实体与 Mapper
  - `rag-common/.../entity/IndexCleanupTaskEntity.java`
  - `rag-common/.../mapper/IndexCleanupTaskMapper.java`
  - `rag-common/src/main/resources/mapper/IndexCleanupTaskMapper.xml`（insert / selectPending / markDone / markFailed / logicDeleteByDocumentId / logicDeleteByKbId / purgeFinishedBefore）
  - 验证：`mvn -f rag-common/pom.xml clean install -DskipTests`
- [x] A3 补齐游标与轻量查询
  - `KnowledgeBaseMapper#selectAllAfterId` + XML
  - `DocumentMapper#selectIdsByKbIdAfterId` + XML
  - `ChunkMapper#selectChildIdsByDocumentId` + XML
  - 验证：编译通过；XML 列名与实体一致
- [x] A4 索引读取方法
  - `EsIndexer#listChunkIds(index, documentId, cap)`：`search_after` 分页、`_source` 仅 `chunkId`、返回 `ChunkIdPage(ids, truncated)`
  - `MilvusIndexer#listChunkIds(collection, documentId, cap)`：`outputFields(chunk_id)`、`limit <= 16384`
  - 验证：`mvn -f rag-worker/pom.xml clean compile -DskipTests`
- [x] A5 新增巡检服务
  - `rag-worker/.../reconcile/IndexReconcileService.java`：调度、Redis 锁、KB/文档游标、预算器、dry-run、删除孤儿、报告落 Redis、补偿任务排空、`purgeFinishedBefore`
  - `rag-worker/src/main/resources/application.yml` 增加 `rag.reconcile.*`（默认 `enabled=false`、`dry-run=true`）
  - 验证：默认配置启动 Worker，日志无 ES/Milvus 对账调用
- [x] A6 删除链路补偿
  - `DocumentAppService#delete`：Milvus 删除失败时写 `index_cleanup_task`（target=MILVUS，status=PENDING），ES 仍 fail-closed
  - 注入 `IndexCleanupTaskMapper`
  - 验证：单测/手工让 Milvus 不可达，任务表出现 PENDING 行
- [x] A7 报告查询接口
  - `PipelineOpsService#lastReconcileReport()` 读取 Redis `rag:reconcile:last`
  - `PipelineOpsController` 新增 `GET /api/v1/ops/pipeline/reconcile/last`（`AuthGuard.requirePlatform()`）
  - 验证：无报告时返回 `available=false`；平台管理员可读，租户用户 403/404
- [x] A8 单元测试
  - `rag-worker/src/test/java/com/rag/worker/reconcile/IndexReconcileServiceTest.java`（10 个用例，见 spec 1.10；含 delete-kb-ids 白名单）
  - 验证：`mvn -f rag-worker/pom.xml clean test`
- [x] A9 集成验证（本地 compose，3307/6380 端口避开占用；真实 ES 8.14 + Milvus 2.5 通过）
  - 按 spec 1.10 手工步骤 1–5 执行，记录报告 JSON
  - 结果：dry-run 报告 `orphanEs=1, orphanMilvus=1, deleted*=0`；`delete-kb-ids` 删除轮 `deletedEs=1, deletedMilvus=1`，MySQL 切片数仍为 1
  - 另发现并修复 `KnowledgeBaseMapper.xml` 双重 UTF-8 BOM（否则运行期 XML 解析失败、应用无法启动）

## 阶段 B：前端组件拆分（纯重构）

- [x] B1 抽取上传 Hook（单独提交）
  - 新增 `knowledge-console/src/hooks/useMultipartUpload.ts`
  - 迁移 `FileLibraryPage.tsx` 458-548 与上传状态（73-76、95-98）
  - 页面改为调用 `const { uploads, beforeUpload } = useMultipartUpload({ onCompleted: () => load(kw) })`
  - 验证：`npm run typecheck` + `npm run build`；手工验证断点续传/秒传/批内去重
- [x] B2 抽取视频播放弹窗（单独提交）
  - 新增 `components/library/VideoPlayerModal.tsx`：HLS、清晰度、全屏、缩放、快捷键、恢复进度、关闭保存；`CtrlBtn/SkipIcon/SeekBar` 同文件
  - 页面保留 WS 单连接，向弹窗传 `progressEvent`、`token`、`onClose`、`onSubscribe`、`onStartTranscode/onStopTranscode`
  - 验证：`npm run typecheck` + `npm run build`；手工验证播放/清晰度/全屏/缩放/恢复/关闭保存/转码进度
- [x] B3 抽取文档预览弹窗
  - 新增 `components/library/DocumentPreviewModal.tsx`：图片/Markdown/文本/音频 + 字幕 Tab
  - 页面按 `previewKind` 路由到视频或文档弹窗
  - 验证：`npm run typecheck` + `npm run build`；手工验证四类预览与字幕入口
- [x] B4 页面收口与清理
  - `FileLibraryPage.tsx` 收敛为列表/搜索/上传/行操作/WS/历史（1309 → 477 行，两张 Table 列定义占主要体积）
  - 清理迁移产生的孤儿 import；逻辑与状态部分已收敛（Table 列定义按原样保留，故实际 477 行）
  - 验证：`wc -l` 对比；`npm run typecheck`；全量手工清单（spec 2.5）

## 阶段 C：音视频流式结论

- [x] C1 将 spec 第 3 节结论同步到 OPTIMIZE.md 对应条目（仅追加“已覆盖/不重复实现”说明，不改其他内容）
  - 验证：diff 仅涉及该条目说明行
- 无代码变更任务

## 阶段 D：整体验证与提交

- [x] D1 后端全量构建与测试
  - `mvn clean install -DskipTests`
  - `mvn -f rag-worker/pom.xml clean test`
  - `mvn -f rag-api/pom.xml clean test`
- [x] D2 前端验证
  - `cd knowledge-console; npm run typecheck; npm run build`
- [x] D3 提交
  - `feat: 索引一致性巡检与孤儿清理`
  - `refactor: 拆分文件库上传与播放组件`
  - 文档：`docs: 补充 OPTIMIZE P2 计划与规格`
- [x] D4 上线参数确认（已在本地 compose 实测生效）
  - 首轮：`RAG_RECONCILE_ENABLED=true` + `RAG_RECONCILE_DRY_RUN=true`，每轮 1 个 KB（`RAG_RECONCILE_MAX_KBS=1`）
  - 观察 `GET /api/v1/ops/pipeline/reconcile/last`：先确认目标 KB 的报告 `errors=[]` 且孤儿数符合预期
  - 开启删除：`RAG_RECONCILE_DRY_RUN=false` + `RAG_RECONCILE_DELETE_KB_IDS=<已确认的kbId,逗号分隔>`，未列入白名单的 KB 仍只统计不删除
  - 回滚：`RAG_RECONCILE_ENABLED=false` 立即停止巡检，不影响主链路

## 全局验收

- 默认配置下系统行为与改动前完全一致（巡检关闭、无额外外部调用）。
- 开启巡检后单轮外部调用有界、可中断、可续跑，且报告可查。
- 前端三个页面不再有单体巨型文件，构建与手工清单全绿。
- 无新增安全风险：巡检只删索引孤儿，不跨租户、不读正文、不打印敏感信息。
