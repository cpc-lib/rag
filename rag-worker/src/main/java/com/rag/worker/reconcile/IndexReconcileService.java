package com.rag.worker.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.IndexCleanupTaskEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.IndexCleanupTaskMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.search.IndexNames;
import com.rag.worker.infrastructure.search.ChunkIdPage;
import com.rag.worker.infrastructure.search.EsIndexer;
import com.rag.worker.infrastructure.search.MilvusIndexer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 索引一致性巡检：比对 MySQL 有效 CHILD 切片与 ES/Milvus 中的 chunkId，
 * 清理孤儿索引并排空删除失败补偿任务；全过程受外部调用预算约束，默认只读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexReconcileService {

    private static final String KEY_LOCK = "rag:reconcile:lock";
    private static final String KEY_CURSOR = "rag:reconcile:cursor";
    private static final String KEY_DOC_CURSOR_PREFIX = "rag:reconcile:doc:";
    private static final String KEY_REPORT = "rag:reconcile:last";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final IndexCleanupTaskMapper cleanupTaskMapper;
    private final EsIndexer esIndexer;
    private final MilvusIndexer milvusIndexer;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${rag.reconcile.enabled:false}")
    private boolean enabled = false;
    @Value("${rag.reconcile.dry-run:true}")
    private boolean dryRun = true;
    @Value("${rag.reconcile.max-kbs-per-run:1}")
    private int maxKbsPerRun = 1;
    @Value("${rag.reconcile.max-docs-per-kb:200}")
    private int maxDocsPerKb = 200;
    @Value("${rag.reconcile.max-ids-per-doc:10000}")
    private int maxIdsPerDoc = 10000;
    @Value("${rag.reconcile.max-external-calls:2000}")
    private int maxExternalCalls = 2000;
    @Value("${rag.reconcile.max-duration-ms:300000}")
    private long maxDurationMs = 300000;
    @Value("${rag.reconcile.cleanup-task-limit:100}")
    private int cleanupTaskLimit = 100;
    @Value("${rag.reconcile.retention-days:30}")
    private int retentionDays = 30;
    @Value("${rag.reconcile.report-ttl-hours:168}")
    private long reportTtlHours = 168;
    /** 删除白名单：dry-run=false 时仅这些 KB 执行删除；为空表示不限制。 */
    @Value("${rag.reconcile.delete-kb-ids:}")
    private String deleteKbIdsRaw = "";
    private Set<Long> deleteKbIds = Set.of();

    @PostConstruct
    void validateConfig() {
        deleteKbIds = parseKbIds(deleteKbIdsRaw);
        if (maxDocsPerKb * 2 > maxExternalCalls) {
            int adjusted = Math.max(1, maxExternalCalls / 2);
            log.warn("rag.reconcile 预算不匹配：max-docs-per-kb({}) * 2 > max-external-calls({})，自动下调为 {}",
                    maxDocsPerKb, maxExternalCalls, adjusted);
            maxDocsPerKb = adjusted;
        }
    }

    private static Set<Long> parseKbIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("rag.reconcile.delete-kb-ids 含非法值，已忽略: {}", s);
            }
        }
        return ids;
    }

    @Scheduled(fixedDelayString = "${rag.reconcile.interval-ms:3600000}",
            initialDelayString = "${rag.reconcile.initial-delay-ms:300000}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        runOnce();
    }

    /** 执行一轮巡检并写报告；定时与测试共用入口。 */
    public Map<String, Object> runOnce() {
        if (!enabled) {
            return Map.of("enabled", false);
        }
        String runId = UUID.randomUUID().toString();
        if (!acquireLock(runId)) {
            log.info("索引一致性巡检已在其它 Worker 运行，跳过本轮");
            return Map.of("skipped", "locked");
        }
        ReconcileReport report = new ReconcileReport(runId, dryRun);
        ReconcileBudget budget = new ReconcileBudget(maxExternalCalls, maxDurationMs);
        try {
            CleanupResult cleanup = drainCleanupTasks(cleanupTaskLimit, budget);
            report.cleanupTasksDrained = cleanup.drained();
            report.cleanupTasksFailed = cleanup.failed();
            int processed = 0;
            while (processed < Math.max(1, maxKbsPerRun) && !budget.exhausted()) {
                KnowledgeBaseEntity kb = pickNextKb(report);
                if (kb == null) {
                    break;
                }
                ReconcileReport kbReport = reconcileKnowledgeBase(kb, readDocCursor(kb.getId()), budget);
                report.mergeFrom(kbReport);
                if (kbReport.completed) {
                    deleteDocCursor(kb.getId());
                } else if (kbReport.nextDocId > 0) {
                    writeDocCursor(kb.getId(), kbReport.nextDocId);
                }
                processed++;
            }
            try {
                cleanupTaskMapper.purgeFinishedBefore(retentionDays);
            } catch (Exception e) {
                log.warn("索引补偿任务清理失败: {}", e.getMessage());
            }
            report.externalCalls = budget.calls();
            report.budgetExhausted = budget.exhausted();
            report.finish();
            publish(report);
            log.info("索引一致性巡检完成 runId={} kbs={} docs={} calls={} budgetExhausted={} "
                            + "orphanEs={} orphanMilvus={} missingEs={} missingMilvus={} deletedEs={} deletedMilvus={}",
                    runId, report.kbIds, report.documentsScanned, report.externalCalls, report.budgetExhausted,
                    report.orphanEs, report.orphanMilvus, report.missingEs, report.missingMilvus,
                    report.deletedEs, report.deletedMilvus);
            return report.toMap();
        } catch (Exception e) {
            report.errors.add("run: " + e.getMessage());
            report.externalCalls = budget.calls();
            report.budgetExhausted = budget.exhausted();
            report.finish();
            publish(report);
            log.error("索引一致性巡检失败 runId={}", runId, e);
            return report.toMap();
        } finally {
            releaseLock(runId);
        }
    }

    // ---------- 补偿任务 ----------

    public record CleanupResult(int drained, int failed) {
    }

    /** 排空删除失败补偿任务；文档仍存在则标记完成并跳过，避免删除活数据。 */
    CleanupResult drainCleanupTasks(int limit, ReconcileBudget budget) {
        List<IndexCleanupTaskEntity> tasks;
        try {
            tasks = cleanupTaskMapper.selectPending(Math.max(1, limit));
        } catch (Exception e) {
            log.warn("读取索引补偿任务失败: {}", e.getMessage());
            return new CleanupResult(0, 0);
        }
        if (tasks == null || tasks.isEmpty()) {
            return new CleanupResult(0, 0);
        }
        int drained = 0;
        int failed = 0;
        for (IndexCleanupTaskEntity task : tasks) {
            if (budget.exhausted()) {
                break;
            }
            try {
                DocumentEntity document = documentMapper.selectById(task.getDocumentId());
                if (document != null) {
                    // 文档仍有效：删除事务已回滚，索引数据应保留
                    cleanupTaskMapper.markDone(task.getId());
                    drained++;
                    continue;
                }
                KnowledgeBaseEntity kb = knowledgeBaseMapper.selectById(task.getKbId());
                if (kb == null) {
                    cleanupTaskMapper.markDone(task.getId());
                    drained++;
                    continue;
                }
                String target = task.getTarget() == null ? "BOTH" : task.getTarget().toUpperCase();
                boolean both = "BOTH".equals(target);
                if (both || "ES".equals(target)) {
                    budget.spend(1);
                    esIndexer.deleteByDocument(IndexNames.physical(kb.getEsIndex(), kb.getIndexVersion()),
                            task.getDocumentId());
                }
                if (both || "MILVUS".equals(target)) {
                    budget.spend(1);
                    milvusIndexer.deleteByDocument(IndexNames.physical(kb.getMilvusCollection(), kb.getIndexVersion()),
                            task.getDocumentId());
                }
                cleanupTaskMapper.markDone(task.getId());
                drained++;
            } catch (Exception e) {
                failed++;
                cleanupTaskMapper.markFailed(task.getId(), truncate(e.getMessage()));
                log.warn("索引补偿任务执行失败 id={} doc={}: {}", task.getId(), task.getDocumentId(), e.getMessage());
            }
        }
        return new CleanupResult(drained, failed);
    }

    // ---------- 单库对账 ----------

    ReconcileReport reconcileKnowledgeBase(KnowledgeBaseEntity kb, long afterDocId, ReconcileBudget budget) {
        ReconcileReport report = new ReconcileReport(null, dryRun);
        report.kbId = kb.getId();
        report.tenantId = kb.getTenantId();
        report.indexVersion = kb.getIndexVersion();
        report.kbIds.add(kb.getId());
        String esIndex = IndexNames.physical(kb.getEsIndex(), kb.getIndexVersion());
        String milvusCollection = IndexNames.physical(kb.getMilvusCollection(), kb.getIndexVersion());
        boolean deleteAllowed = !dryRun && (deleteKbIds.isEmpty() || deleteKbIds.contains(kb.getId()));
        if (!dryRun && !deleteAllowed) {
            log.info("巡检跳过删除：kb={} 不在 delete-kb-ids 白名单", kb.getId());
        }
        long cursor = Math.max(0L, afterDocId);
        int scanned = 0;
        while (scanned < maxDocsPerKb) {
            if (budget.exhausted()) {
                report.budgetExhausted = true;
                break;
            }
            int pageSize = Math.min(50, maxDocsPerKb - scanned);
            List<Long> documentIds;
            try {
                documentIds = documentMapper.selectIdsByKbIdAfterId(kb.getId(), cursor, pageSize);
            } catch (Exception e) {
                report.errors.add("kb=" + kb.getId() + " 文档查询失败: " + e.getMessage());
                break;
            }
            if (documentIds == null || documentIds.isEmpty()) {
                report.completed = true;
                break;
            }
            boolean abort = false;
            for (Long documentId : documentIds) {
                if (budget.exhausted()) {
                    report.budgetExhausted = true;
                    break;
                }
                try {
                    reconcileDocument(esIndex, milvusCollection, documentId, deleteAllowed, budget, report);
                } catch (Exception e) {
                    report.errors.add("doc=" + documentId + ": " + e.getMessage());
                    abort = true;
                    break;
                }
                cursor = documentId;
                scanned++;
                if (budget.exhausted()) {
                    report.budgetExhausted = true;
                    break;
                }
            }
            if (abort || budget.exhausted() || scanned >= maxDocsPerKb) {
                break;
            }
        }
        report.documentsScanned = scanned;
        report.nextDocId = cursor;
        return report;
    }

    private void reconcileDocument(String esIndex, String milvusCollection, long documentId,
                                   boolean deleteAllowed, ReconcileBudget budget, ReconcileReport report) {
        List<Long> expectedIds = chunkMapper.selectChildIdsByDocumentId(documentId);
        Set<Long> expected = expectedIds == null ? Set.of() : new HashSet<>(expectedIds);

        ChunkIdPage es = esIndexer.listChunkIds(esIndex, documentId, maxIdsPerDoc);
        budget.spend(Math.max(1, es.calls()));
        ChunkIdPage milvus = milvusIndexer.listChunkIds(milvusCollection, documentId, maxIdsPerDoc);
        budget.spend(Math.max(1, milvus.calls()));
        if (budget.exhausted()) {
            return;
        }

        if (es.truncated() || milvus.truncated()) {
            // 索引侧结果可能不完整，禁止据此删除
            report.truncatedDocs.add(documentId);
            report.documentsSkipped++;
            return;
        }
        if (es.indexMissing()) {
            report.missingEs += expected.size();
        }
        if (milvus.indexMissing()) {
            report.missingMilvus += expected.size();
        }
        if (es.indexMissing() && milvus.indexMissing()) {
            report.documentsSkipped++;
            return;
        }

        List<Long> orphanEs = es.indexMissing() ? List.of() : subtract(es.ids(), expected);
        List<Long> orphanMilvus = milvus.indexMissing() ? List.of() : subtract(milvus.ids(), expected);
        report.orphanEs += orphanEs.size();
        report.orphanMilvus += orphanMilvus.size();
        if (!es.indexMissing()) {
            report.missingEs += subtract(expected, es.ids()).size();
        }
        if (!milvus.indexMissing()) {
            report.missingMilvus += subtract(expected, milvus.ids()).size();
        }
        if (!deleteAllowed) {
            return;
        }

        if (!es.indexMissing()) {
            if (expected.isEmpty()) {
                if (!es.ids().isEmpty()) {
                    if (!budget.spend(1)) {
                        return;
                    }
                    esIndexer.deleteByDocument(esIndex, documentId);
                    report.deletedEs += es.ids().size();
                }
            } else if (!orphanEs.isEmpty()) {
                if (!budget.spend(1)) {
                    return;
                }
                esIndexer.deleteStale(esIndex, documentId, expectedIds);
                report.deletedEs += orphanEs.size();
            }
        }
        if (!milvus.indexMissing()) {
            if (expected.isEmpty()) {
                if (!milvus.ids().isEmpty()) {
                    if (!budget.spend(1)) {
                        return;
                    }
                    milvusIndexer.deleteByDocument(milvusCollection, documentId);
                    report.deletedMilvus += milvus.ids().size();
                }
            } else if (!orphanMilvus.isEmpty()) {
                if (!budget.spend(1)) {
                    return;
                }
                milvusIndexer.deleteStale(milvusCollection, documentId, expectedIds);
                report.deletedMilvus += orphanMilvus.size();
            }
        }
    }

    private static List<Long> subtract(List<Long> actual, Set<Long> expected) {
        List<Long> result = new ArrayList<>();
        if (actual == null) {
            return result;
        }
        for (Long id : actual) {
            if (id != null && !expected.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private static List<Long> subtract(Set<Long> expected, List<Long> actual) {
        Set<Long> actualSet = actual == null ? Set.of() : new HashSet<>(actual);
        List<Long> result = new ArrayList<>();
        for (Long id : expected) {
            if (!actualSet.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    // ---------- 知识库选择与游标 ----------

    private KnowledgeBaseEntity pickNextKb(ReconcileReport report) {
        long cursor = readCursor();
        int batch = Math.max(1, maxKbsPerRun) + 10;
        List<KnowledgeBaseEntity> candidates = knowledgeBaseMapper.selectAllAfterId(cursor, batch);
        if (candidates == null || candidates.isEmpty()) {
            cursor = 0L;
            candidates = knowledgeBaseMapper.selectAllAfterId(cursor, batch);
        }
        if (candidates == null || candidates.isEmpty()) {
            writeCursor(0L);
            return null;
        }
        for (KnowledgeBaseEntity kb : candidates) {
            writeCursor(kb.getId());
            if ("BUILDING".equalsIgnoreCase(kb.getIndexState())) {
                report.skippedKbs.add(String.valueOf(kb.getId()));
                continue;
            }
            return kb;
        }
        return null;
    }

    private long readCursor() {
        try {
            String value = redis.opsForValue().get(KEY_CURSOR);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("读取巡检游标失败，按 0 处理: {}", e.getMessage());
            return 0L;
        }
    }

    private void writeCursor(long kbId) {
        try {
            redis.opsForValue().set(KEY_CURSOR, String.valueOf(kbId));
        } catch (Exception e) {
            log.warn("写入巡检游标失败: {}", e.getMessage());
        }
    }

    private long readDocCursor(long kbId) {
        try {
            String value = redis.opsForValue().get(KEY_DOC_CURSOR_PREFIX + kbId);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private void writeDocCursor(long kbId, long documentId) {
        try {
            redis.opsForValue().set(KEY_DOC_CURSOR_PREFIX + kbId, String.valueOf(documentId), Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("写入文档游标失败 kb={}: {}", kbId, e.getMessage());
        }
    }

    private void deleteDocCursor(long kbId) {
        try {
            redis.delete(KEY_DOC_CURSOR_PREFIX + kbId);
        } catch (Exception e) {
            log.warn("删除文档游标失败 kb={}: {}", kbId, e.getMessage());
        }
    }

    // ---------- 锁与报告 ----------

    private boolean acquireLock(String runId) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(KEY_LOCK, runId,
                    Duration.ofMillis(maxDurationMs + 60000));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("获取巡检锁失败，跳过本轮: {}", e.getMessage());
            return false;
        }
    }

    private void releaseLock(String runId) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(KEY_LOCK), runId);
        } catch (Exception e) {
            log.warn("释放巡检锁失败: {}", e.getMessage());
        }
    }

    private void publish(ReconcileReport report) {
        try {
            redis.opsForValue().set(KEY_REPORT, objectMapper.writeValueAsString(report.toMap()),
                    Duration.ofHours(reportTtlHours));
        } catch (Exception e) {
            log.warn("写入巡检报告失败: {}", e.getMessage());
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
