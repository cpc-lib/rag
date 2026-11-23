package com.rag.worker.reconcile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 索引一致性巡检报告（单轮，可合并多个知识库）。 */
public class ReconcileReport {

    public String runId;
    public String startedAt;
    public String finishedAt;
    public boolean dryRun;
    public Long kbId;
    public String tenantId;
    public Integer indexVersion;
    public final List<Long> kbIds = new ArrayList<>();
    public int documentsScanned;
    public int documentsSkipped;
    public int orphanEs;
    public int orphanMilvus;
    public int missingEs;
    public int missingMilvus;
    public int deletedEs;
    public int deletedMilvus;
    public int cleanupTasksDrained;
    public int cleanupTasksFailed;
    public int externalCalls;
    public boolean budgetExhausted;
    public boolean completed;
    public long nextDocId;
    public final List<Long> truncatedDocs = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> skippedKbs = new ArrayList<>();

    public ReconcileReport(String runId, boolean dryRun) {
        this.runId = runId;
        this.dryRun = dryRun;
        this.startedAt = OffsetDateTime.now().toString();
    }

    public void finish() {
        this.finishedAt = OffsetDateTime.now().toString();
    }

    public void mergeFrom(ReconcileReport other) {
        if (other == null) {
            return;
        }
        documentsScanned += other.documentsScanned;
        documentsSkipped += other.documentsSkipped;
        orphanEs += other.orphanEs;
        orphanMilvus += other.orphanMilvus;
        missingEs += other.missingEs;
        missingMilvus += other.missingMilvus;
        deletedEs += other.deletedEs;
        deletedMilvus += other.deletedMilvus;
        budgetExhausted = budgetExhausted || other.budgetExhausted;
        truncatedDocs.addAll(other.truncatedDocs);
        errors.addAll(other.errors);
        if (other.kbId != null) {
            kbId = other.kbId;
            kbIds.add(other.kbId);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        m.put("dryRun", dryRun);
        m.put("kbId", kbId);
        m.put("kbIds", kbIds);
        m.put("tenantId", tenantId);
        m.put("indexVersion", indexVersion);
        m.put("documentsScanned", documentsScanned);
        m.put("documentsSkipped", documentsSkipped);
        m.put("orphanEs", orphanEs);
        m.put("orphanMilvus", orphanMilvus);
        m.put("missingEs", missingEs);
        m.put("missingMilvus", missingMilvus);
        m.put("deletedEs", deletedEs);
        m.put("deletedMilvus", deletedMilvus);
        m.put("cleanupTasksDrained", cleanupTasksDrained);
        m.put("cleanupTasksFailed", cleanupTasksFailed);
        m.put("externalCalls", externalCalls);
        m.put("budgetExhausted", budgetExhausted);
        m.put("completed", completed);
        m.put("truncatedDocs", truncatedDocs.stream().limit(20).toList());
        m.put("errors", errors.stream().limit(20).toList());
        m.put("skippedKbs", skippedKbs.stream().limit(20).toList());
        return m;
    }
}
