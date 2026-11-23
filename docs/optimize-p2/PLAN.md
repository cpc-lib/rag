# OPTIMIZE P2 落地计划（三项遗留项）

> 对应 [OPTIMIZE.md](../../OPTIMIZE.md) 的 4.3 / 4.4，以及“视频/音频流式预览”结论项。
> 本文只做计划与拆解；**确认后再进入编码**。规格细节见 [spec.md](./spec.md)，任务清单见 [tasks.md](./tasks.md)。

## 1. 目标与范围

| 编号 | 事项 | 本次目标 | 是否写代码 |
|---|---|---|---|
| A | ES/Milvus 与 MySQL 定时一致性对账 + 孤儿清理 | 新增巡检服务、补偿任务、外部调用预算、只读报告接口 | 是 |
| B | `FileLibraryPage` 等超大单文件组件拆分 | 纯重构，行为不变，先评估风险收益再动手 | 是 |
| C | 音视频预览走流式 | 核查现状并固化结论：已由文件库 stream/HLS 覆盖 | 否（仅结论） |

**明确不做**：不重写检索链路、不改切片策略、不改索引 schema、不做前端样式重构、不删除历史死代码（`FilePreviewPage` 的 video/audio 分支保留）、不引入新的调度框架或消息队列。

## 2. 关键决策（默认值，可在评审时调整）

| 决策 | 选择 | 理由 |
|---|---|---|
| D1 对账执行位置 | `rag-worker` 新增 `IndexReconcileService` | 该模块已有 `@EnableScheduling`、`EsIndexer`/`MilvusIndexer`、全部 Mapper，避免 API 再引入写索引能力 |
| D2 默认开关 | `rag.reconcile.enabled=false`、`dry-run=true` | 巡检涉及外部调用，默认不产生成本；首轮只观测不删除 |
| D3 巡检粒度 | 每轮处理 1 个知识库，按 id 游标轮转；KB 内按文档游标断点续跑 | 保证单轮外部调用有界、可恢复 |
| D4 报告存放 | Worker 写 Redis `rag:reconcile:last`（TTL 7 天），API 只读查询 | 不新增报表表；复用两端已有 Redis |
| D5 删除失败的补偿 | 新增表 `index_cleanup_task`（Flyway V2） | 与 `pipeline_task` 状态机解耦，避免被僵尸任务扫描误投递 |
| D6 ES 删除失败策略 | 保持 fail-closed（抛错、删除事务不提交）；仅 Milvus 失败入补偿表 | 最小行为变更：ES 是删除第一步，失败时不产生孤儿 |
| D7 缺失索引修复 | 仅报告，不自动重建（`repair-missing=false`） | 自动重嵌成本不可控，交由人工或既有“重建索引”流程 |
| D8 前端拆分节奏 | 两个独立提交：①上传 Hook ②播放器/预览弹窗 | 缩小回归面，可单独回滚 |

## 3. 阶段与交付物

### 阶段 0：评审（当前）
- 交付物：本 PLAN、[spec.md](./spec.md)、[tasks.md](./tasks.md)
- 验收：三项范围、默认开关、补偿表方案、前端拆分边界获得确认

### 阶段 1：对账与孤儿清理（A）
1. 数据模型：`V2__index_cleanup_task.sql` + 实体/Mapper/XML。
2. 查询能力：KB 游标分页、文档游标分页、轻量 CHILD id 查询。
3. 索引读取：ES `search_after` 分页列 id；Milvus 按文档列 `chunk_id`（上限保护）。
4. `IndexReconcileService`：预算器、游标、Redis 锁、dry-run、删除孤儿、补偿任务排空、报告落 Redis。
5. `DocumentAppService.delete`：Milvus 失败入补偿表。
6. API：`GET /api/v1/ops/pipeline/reconcile/last`（平台管理员只读）。
7. 测试：预算、孤儿计算、dry-run、空切片、DB 异常中止、BUILDING 跳过、补偿排空。

**验收标准**
- `mvn -f rag-common/pom.xml clean install -DskipTests` 通过。
- `mvn -f rag-worker/pom.xml clean test` 通过（新增用例全绿）。
- 默认配置启动 Worker 不产生任何 ES/Milvus 调用（enabled=false）。
- 手工注入 1 条孤儿 ES 文档 + 1 条孤儿向量：dry-run 报告计数正确且未删除；`dry-run=false` 后孤儿被删除，MySQL 切片数不变。
- 单轮外部调用次数 ≤ `max-external-calls`，超时/超预算能正常结束并写报告。

### 阶段 2：前端组件拆分（B）
1. `hooks/useMultipartUpload.ts`：上传会话、SHA-256、分片、断点续传、批内去重、进度与速度。
2. `components/library/VideoPlayerModal.tsx`：HLS、清晰度、全屏、缩放、快捷键、恢复进度、关闭保存。
3. `components/library/DocumentPreviewModal.tsx`：图片/Markdown/文本/音频预览 + 字幕入口。
4. `FileLibraryPage.tsx` 收口为列表/搜索/上传/行操作/WS 订阅。

**验收标准**
- `npm run typecheck`、`npm run build` 通过。
- 手工清单全过（列表、搜索、上传断点/秒传、图片/MD/文本/音频预览、视频播放与转码进度、字幕、删除）。
- 页面文件从 1309 行降到约 300 行；无样式与文案变更；主包 chunk 体积不增加。

### 阶段 3：流式预览结论（C）
- 交付物：spec.md 第 3 节（现状证据、结论、残余缺口、未来触发条件）。
- 验收：结论与代码引用一致；无代码变更。

### 阶段 4：整体验证与提交
- 后端：`mvn clean install -DskipTests` + worker/api 单测。
- 前端：`npm run typecheck` + `npm run build`。
- 提交拆分建议：`feat: 索引一致性巡检与孤儿清理`、`refactor: 拆分文件库上传与播放组件`。

## 4. 风险与回滚

| 风险 | 影响 | 缓解 | 回滚 |
|---|---|---|---|
| 巡检误删有效索引 | 检索缺失 | 默认 dry-run；仅在 MySQL 读取成功且双方 id 集合完整时删除；BUILDING 跳过；删除按 chunkId 精确匹配 | 关闭 `enabled`；用既有“重建索引”恢复 |
| Milvus 删除可见性延迟 | 重复清理/计数偏差 | 幂等删除；报告标注 `truncated`；不据此修改 MySQL | 无副作用 |
| 外部调用放大 | ES/Milvus 压力 | 单 KB/单文档/总调用/总时长四重预算；游标续跑 | 调小预算或关闭开关 |
| 新增表迁移失败 | 启动失败 | `CREATE TABLE IF NOT EXISTS`；只由 rag-api 执行 Flyway | 回滚版本，表无外键、可安全丢弃 |
| 播放器拆分回归 | 视频不可播/进度丢失 | 纯搬移不改逻辑；两阶段提交；手工清单覆盖 HLS/全屏/恢复 | revert 对应提交 |
| 上传拆分回归 | 断点续传/秒传失效 | 保留 localStorage key 与去重集合语义；手工验证三类上传 | revert 对应提交 |

## 5. 执行顺序

1. 阶段 0 审批。
2. A-1 数据模型 → A-2 查询 → A-3 索引读取 → A-4 服务 → A-5 删除补偿 → A-6 报告接口 → A-7 测试。
3. B-1 上传 Hook（提一次）→ B-2 播放/预览弹窗（提一次）→ B-3 页面收口。
4. C 结论文档化。
5. 阶段 4 全量验证与提交。

## 6. 假设（需确认）

1. 巡检只在 Worker 运行，API 不直接读写 ES/Milvus。
2. 对账只覆盖 `knowledge_base.index_state != 'BUILDING'` 的知识库。
3. 只对 `chunk_type='CHILD' AND status!='DELETED' AND deleted=0` 的切片做期望集合。
4. 允许新增一张补偿表与一个 Flyway V2 脚本。
5. 前端拆分不改变任何视觉与交互，不做顺手优化。
