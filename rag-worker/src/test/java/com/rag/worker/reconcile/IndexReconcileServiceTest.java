package com.rag.worker.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.IndexCleanupTaskEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.IndexCleanupTaskMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.worker.infrastructure.search.ChunkIdPage;
import com.rag.worker.infrastructure.search.EsIndexer;
import com.rag.worker.infrastructure.search.MilvusIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexReconcileServiceTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private IndexCleanupTaskMapper cleanupTaskMapper;
    @Mock
    private EsIndexer esIndexer;
    @Mock
    private MilvusIndexer milvusIndexer;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private IndexReconcileService service;

    @BeforeEach
    void setUp() {
        service = new IndexReconcileService(knowledgeBaseMapper, documentMapper, chunkMapper, cleanupTaskMapper,
                esIndexer, milvusIndexer, redis, new ObjectMapper());
    }

    private KnowledgeBaseEntity kb(long id, String state) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(id);
        kb.setTenantId("t1");
        kb.setEsIndex("kb_" + id);
        kb.setMilvusCollection("kb_" + id);
        kb.setIndexVersion(0);
        kb.setIndexState(state);
        return kb;
    }

    private void dryRun(boolean value) {
        ReflectionTestUtils.setField(service, "dryRun", value);
    }

    @Test
    void dryRunCountsOrphansWithoutDeleting() {
        dryRun(true);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L));
        when(documentMapper.selectIdsByKbIdAfterId(1L, 100L, 50)).thenReturn(List.of());
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenReturn(List.of(1L, 2L));
        when(esIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L, 2L, 3L), false, false, 2));
        when(milvusIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L, 2L, 4L), false, false, 2));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(100, 100_000));

        assertEquals(1, report.documentsScanned);
        assertEquals(1, report.orphanEs);
        assertEquals(1, report.orphanMilvus);
        assertEquals(0, report.missingEs);
        assertEquals(0, report.missingMilvus);
        assertEquals(0, report.deletedEs);
        assertEquals(0, report.deletedMilvus);
        assertTrue(report.completed);
        verify(esIndexer, never()).deleteStale(anyString(), anyLong(), anyList());
        verify(esIndexer, never()).deleteByDocument(anyString(), anyLong());
        verify(milvusIndexer, never()).deleteStale(anyString(), anyLong(), anyList());
        verify(milvusIndexer, never()).deleteByDocument(anyString(), anyLong());
    }

    @Test
    void deleteModeRemovesOnlyOrphans() {
        dryRun(false);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L));
        when(documentMapper.selectIdsByKbIdAfterId(1L, 100L, 50)).thenReturn(List.of());
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenReturn(List.of(1L, 2L));
        when(esIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L, 2L, 3L), false, false, 2));
        when(milvusIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L, 2L, 4L), false, false, 2));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(100, 100_000));

        assertEquals(1, report.deletedEs);
        assertEquals(1, report.deletedMilvus);
        verify(esIndexer).deleteStale("kb_1", 100L, List.of(1L, 2L));
        verify(milvusIndexer).deleteStale("kb_1", 100L, List.of(1L, 2L));
    }

    @Test
    void emptyExpectedDeletesWholeDocumentIndex() {
        dryRun(false);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L));
        when(documentMapper.selectIdsByKbIdAfterId(1L, 100L, 50)).thenReturn(List.of());
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenReturn(List.of());
        when(esIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(9L), false, false, 2));
        when(milvusIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(), false, false, 2));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(100, 100_000));

        assertEquals(1, report.deletedEs);
        assertEquals(0, report.deletedMilvus);
        verify(esIndexer).deleteByDocument("kb_1", 100L);
        verify(milvusIndexer, never()).deleteByDocument(anyString(), anyLong());
    }

    @Test
    void truncatedIndexPageNeverDeletes() {
        dryRun(false);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L));
        when(documentMapper.selectIdsByKbIdAfterId(1L, 100L, 50)).thenReturn(List.of());
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenReturn(List.of(1L));
        when(esIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L, 2L), true, false, 2));
        when(milvusIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L), false, false, 2));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(100, 100_000));

        assertTrue(report.truncatedDocs.contains(100L));
        assertEquals(1, report.documentsSkipped);
        verify(esIndexer, never()).deleteStale(anyString(), anyLong(), anyList());
        verify(esIndexer, never()).deleteByDocument(anyString(), anyLong());
        verify(milvusIndexer, never()).deleteByDocument(anyString(), anyLong());
        verify(milvusIndexer, never()).deleteStale(anyString(), anyLong(), anyList());
    }

    @Test
    void mysqlFailureAbortsWithoutDeleting() {
        dryRun(false);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L));
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenThrow(new IllegalStateException("db down"));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(100, 100_000));

        assertFalse(report.errors.isEmpty());
        assertEquals(0, report.deletedEs);
        assertEquals(0, report.deletedMilvus);
        verifyNoInteractions(esIndexer);
    }

    @Test
    void budgetExhaustionStopsAtDocumentBoundary() {
        dryRun(false);
        KnowledgeBaseEntity kb = kb(1, "READY");
        when(documentMapper.selectIdsByKbIdAfterId(1L, 0L, 50)).thenReturn(List.of(100L, 101L));
        when(chunkMapper.selectChildIdsByDocumentId(100L)).thenReturn(List.of(1L));
        when(esIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L), false, false, 2));
        when(milvusIndexer.listChunkIds("kb_1", 100L, 10000))
                .thenReturn(new ChunkIdPage(List.of(1L), false, false, 2));

        ReconcileReport report = service.reconcileKnowledgeBase(kb, 0L, new ReconcileBudget(1, 100_000));

        assertTrue(report.budgetExhausted);
        assertEquals(1, report.documentsScanned);
        assertEquals(100L, report.nextDocId);
        verify(chunkMapper, never()).selectChildIdsByDocumentId(101L);
    }

    @Test
    void buildingKnowledgeBaseIsSkipped() {
        ReflectionTestUtils.setField(service, "enabled", true);
        dryRun(true);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("rag:reconcile:lock"), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.get("rag:reconcile:cursor")).thenReturn("0");
        when(knowledgeBaseMapper.selectAllAfterId(0L, 11))
                .thenReturn(List.of(kb(1, "BUILDING"), kb(2, "READY")));
        when(cleanupTaskMapper.selectPending(100)).thenReturn(List.of());
        when(documentMapper.selectIdsByKbIdAfterId(2L, 0L, 50)).thenReturn(List.of());

        Map<String, Object> result = service.runOnce();

        assertEquals(List.of(2L), result.get("kbIds"));
        assertEquals(List.of("1"), result.get("skippedKbs"));
    }

    @Test
    void cleanupTasksSkipLiveDocumentAndDrainDeletedDocument() {
        IndexCleanupTaskEntity live = cleanupTask(1L, 100L);
        IndexCleanupTaskEntity deleted = cleanupTask(2L, 200L);
        when(cleanupTaskMapper.selectPending(10)).thenReturn(List.of(live, deleted));
        when(documentMapper.selectById(100L)).thenReturn(new DocumentEntity());
        when(documentMapper.selectById(200L)).thenReturn(null);
        when(knowledgeBaseMapper.selectById(9L)).thenReturn(kb(9, "READY"));

        IndexReconcileService.CleanupResult result = service.drainCleanupTasks(10, new ReconcileBudget(100, 100_000));

        assertEquals(2, result.drained());
        assertEquals(0, result.failed());
        verify(cleanupTaskMapper).markDone(1L);
        verify(cleanupTaskMapper).markDone(2L);
        verify(esIndexer).deleteByDocument("kb_9", 200L);
        verify(milvusIndexer).deleteByDocument("kb_9", 200L);
        verify(esIndexer, never()).deleteByDocument("kb_9", 100L);
    }

    @Test
    void cleanupTaskFailureIsRecorded() {
        IndexCleanupTaskEntity task = cleanupTask(3L, 300L);
        when(cleanupTaskMapper.selectPending(10)).thenReturn(List.of(task));
        when(documentMapper.selectById(300L)).thenReturn(null);
        when(knowledgeBaseMapper.selectById(9L)).thenReturn(kb(9, "READY"));
        doThrow(new IllegalStateException("es down")).when(esIndexer).deleteByDocument("kb_9", 300L);

        IndexReconcileService.CleanupResult result = service.drainCleanupTasks(10, new ReconcileBudget(100, 100_000));

        assertEquals(0, result.drained());
        assertEquals(1, result.failed());
        verify(cleanupTaskMapper).markFailed(eq(3L), anyString());
    }

    private IndexCleanupTaskEntity cleanupTask(long id, long documentId) {
        IndexCleanupTaskEntity task = new IndexCleanupTaskEntity();
        task.setId(id);
        task.setTenantId("t1");
        task.setKbId(9L);
        task.setDocumentId(documentId);
        task.setTarget("BOTH");
        task.setStatus("PENDING");
        task.setAttempts(0);
        return task;
    }
}
