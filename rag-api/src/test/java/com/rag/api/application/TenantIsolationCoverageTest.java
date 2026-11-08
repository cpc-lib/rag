package com.rag.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.llm.RerankClient;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.mq.Sha256Publisher;
import com.rag.api.infrastructure.persistence.entity.ChatSessionEntity;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import com.rag.api.infrastructure.persistence.mapper.ChatMessageMapper;
import com.rag.api.infrastructure.persistence.mapper.ChatSessionMapper;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.infrastructure.persistence.mapper.PromptTemplateMapper;
import com.rag.api.infrastructure.persistence.mapper.SysMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.SysToolMapper;
import com.rag.api.infrastructure.persistence.mapper.SysUserMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.UserFeatureMapper;
import com.rag.api.infrastructure.persistence.mapper.UserKbMapper;
import com.rag.api.infrastructure.persistence.mapper.UserPromptMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.rag.api.interfaces.dto.Dtos;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG-010：文档 / 切片 / 会话 / 提示词 / 索引 / 预签名 URL 的租户穿透回归。
 * 跨租户统一断言 NOT_FOUND，且失败发生在读写外部存储之前。
 */
@ExtendWith(MockitoExtension.class)
class TenantIsolationCoverageTest {

    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private PipelineTaskMapper taskMapper;
    @Mock
    private ModelService modelService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private MilvusClientWrapper milvus;
    @Mock
    private EsSearchClient es;
    @Mock
    private MinioStorage minio;
    @Mock
    private IngestPublisher publisher;
    @Mock
    private Sha256Publisher sha256Publisher;
    @Mock
    private FileLibraryService fileLibraryService;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private UserKbMapper userKbMapper;
    @Mock
    private UserPromptMapper userPromptMapper;
    @Mock
    private UserFeatureMapper userFeatureMapper;
    @Mock
    private PromptTemplateMapper promptMapper;
    @Mock
    private SysMenuMapper sysMenuMapper;
    @Mock
    private SysToolMapper sysToolMapper;
    @Mock
    private TenantMenuMapper tenantMenuMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LlmClient llmClient;
    @Mock
    private RerankClient rerankClient;
    @Mock
    private EtcdService etcdService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private KnowledgeBaseService knowledgeBaseService;
    private UserManageService userManageService;
    private DocumentAppService documentAppService;
    private ChunkAppService chunkAppService;
    private ChatSessionService chatSessionService;
    private PromptTemplateService promptTemplateServiceUnderTest;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        userManageService = new UserManageService(userMapper, userKbMapper, userPromptMapper,
                userFeatureMapper, promptMapper, kbMapper, sysMenuMapper, sysToolMapper,
                tenantMenuMapper, passwordEncoder);
        knowledgeBaseService = new KnowledgeBaseService(kbMapper, documentMapper, chunkMapper,
                modelService, milvus, es, minio, objectMapper, userManageService,
                promptTemplateService, fileLibraryService, taskMapper, publisher);
        documentAppService = new DocumentAppService(documentMapper, chunkMapper, taskMapper,
                knowledgeBaseService, quotaService, modelService, minio, es, milvus,
                publisher, objectMapper, fileLibraryService, sha256Publisher);
        chunkAppService = new ChunkAppService(chunkMapper, documentMapper, taskMapper,
                knowledgeBaseService, publisher);
        chatSessionService = new ChatSessionService(chatSessionMapper, chatMessageMapper, objectMapper);
        promptTemplateServiceUnderTest = new PromptTemplateService(promptMapper, kbMapper, userPromptMapper);
        retrievalService = new RetrievalService(kbMapper, modelService, chunkMapper, documentMapper,
                llmClient, rerankClient, milvus, es, minio, etcdService, meterRegistry);
        // 读名称统一走别名解析：单测中直接回退逻辑基名，保持原有 stub 参数
        lenient().when(es.resolveReadName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(milvus.resolveReadName(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---------------- 文档 ----------------

    @Test
    void crossTenantDocumentIndexStatusDoesNotProbeIndexes() {
        session("tenant-b", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> documentAppService.indexStatus(11L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(es, milvus);
    }

    @Test
    void crossTenantDocumentDownloadUrlDoesNotPresign() {
        session("tenant-b", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> documentAppService.downloadUrl(11L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(minio, never()).presignUrl(anyString());
    }

    @Test
    void sameTenantDocumentDownloadUrlIsPresigned() {
        session("tenant-a", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));
        when(minio.presignUrl("tenant-a/9/a.txt")).thenReturn("https://minio/a.txt");

        assertEquals("https://minio/a.txt", documentAppService.downloadUrl(11L));
    }

    @Test
    void crossTenantDocumentDeleteDoesNotTouchStorage() {
        session("tenant-b", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        assertThrows(BizException.class, () -> documentAppService.delete(11L));

        verifyNoInteractions(es, minio);
        verify(chunkMapper, never()).logicDeleteByDocumentId(anyLong());
        verify(documentMapper, never()).logicDeleteById(anyLong());
    }

    @Test
    void crossTenantDocumentStartIsRejectedBeforeDispatch() {
        TenantContext.set(new TenantContext.Session(2L, "tenant-b", 1, "user-b"));
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> documentAppService.startProcessing(11L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(publisher, never()).publish(any());
    }

    // ---------------- 切片 ----------------

    @Test
    void crossTenantChunkLookupIsNotFound() {
        session("tenant-b", 2);
        when(chunkMapper.selectById(88L)).thenReturn(chunk(88L, 9L, 11L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> chunkAppService.get(88L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void crossTenantChunkPageIsNotFound() {
        session("tenant-b", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        assertThrows(BizException.class, () -> chunkAppService.page(11L, 1, 20));

        verify(chunkMapper, never()).selectPageByDocumentId(any(), anyLong());
    }

    @Test
    void crossTenantChunkCreateIsRejected() {
        session("tenant-b", 2);
        when(documentMapper.selectById(11L)).thenReturn(doc(11L, 9L, "tenant-a"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        assertThrows(BizException.class, () -> chunkAppService.create(
                new Dtos.ChunkCreateReq(11L, "内容", 0, "标题")));

        verify(chunkMapper, never()).insert(any(ChunkEntity.class));
    }

    // ---------------- 会话 ----------------

    @Test
    void crossTenantOrCrossUserSessionIsNotFound() {
        when(chatSessionMapper.selectById(5L)).thenReturn(chatSession(5L, "tenant-a", 100L, 9L));

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> chatSessionService.detail("tenant-b", 100L, 5L)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> chatSessionService.detail("tenant-a", 101L, 5L)).getErrorCode());
    }

    @Test
    void crossTenantSessionDeleteDoesNotRemoveMessages() {
        when(chatSessionMapper.selectById(5L)).thenReturn(chatSession(5L, "tenant-a", 100L, 9L));

        assertThrows(BizException.class, () -> chatSessionService.delete("tenant-b", 100L, 5L));

        verify(chatMessageMapper, never()).logicDeleteByTenantIdAndSessionId(anyString(), anyLong());
        verify(chatSessionMapper, never()).logicDeleteById(anyLong());
    }

    // ---------------- 提示词 ----------------

    @Test
    void crossTenantPromptListIsNotFound() {
        TenantContext.set(new TenantContext.Session(2L, "tenant-b", 1, "user-b"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> userManageService.visiblePrompts(9L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(promptMapper, never()).selectByKbId(anyLong());
    }

    @Test
    void crossTenantPromptTemplateIsNotFound() {
        PromptTemplateEntity t = new PromptTemplateEntity();
        t.setId(5L);
        t.setTenantId("tenant-a");
        t.setKbId(9L);
        when(promptMapper.selectById(5L)).thenReturn(t);

        BizException ex = assertThrows(BizException.class,
                () -> promptTemplateServiceUnderTest.get("tenant-b", 5L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ---------------- 索引检索路径 ----------------

    @Test
    void indexHitFromAnotherTenantIsDropped() {
        session("tenant-a", 2);
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));
        when(etcdService.retrievalConfig()).thenReturn(new EtcdService.RetrievalConfig());
        // 未启用向量模型 → 走关键词通道，返回一个其他租户的脏 chunk id
        when(modelService.findEnabled("tenant-a", ModelService.EMBEDDING)).thenReturn(null);
        when(es.search("kb_9", "问题", 20)).thenReturn(List.of(77L));
        when(chunkMapper.selectByIds(List.of(77L)))
                .thenReturn(List.of(chunk(77L, 999L, 111L, "tenant-b")));

        RetrievalService.RetrievalResult result = retrievalService.retrieve(
                TenantContext.require(), 9L, "问题");

        assertTrue(result.citations().isEmpty());
        assertFalse(result.vectorAvailable());
        assertTrue(result.keywordAvailable());
        verify(minio, never()).presignUrl(anyString());
        verify(documentMapper, never()).selectByIds(any());
    }

    @Test
    void sameTenantIndexHitProducesCitation() {
        session("tenant-a", 2);
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));
        when(etcdService.retrievalConfig()).thenReturn(new EtcdService.RetrievalConfig());
        when(modelService.findEnabled("tenant-a", ModelService.EMBEDDING)).thenReturn(null);
        when(es.search("kb_9", "问题", 20)).thenReturn(List.of(77L));
        ChunkEntity child = chunk(77L, 9L, 11L, "tenant-a");
        child.setContent("平台支持多租户知识库隔离。");
        child.setContentType("TEXT");
        when(chunkMapper.selectByIds(List.of(77L))).thenReturn(List.of(child));
        DocumentEntity document = doc(11L, 9L, "tenant-a");
        document.setFileName("a.txt");
        when(documentMapper.selectByIds(Set.of(11L))).thenReturn(List.of(document));
        when(minio.presignUrl("tenant-a/9/a.txt")).thenReturn("https://minio/a.txt");

        RetrievalService.RetrievalResult result = retrievalService.retrieve(
                TenantContext.require(), 9L, "问题");

        assertEquals(1, result.citations().size());
        assertEquals("a.txt", result.citations().get(0).documentName());
        assertEquals("https://minio/a.txt", result.citations().get(0).previewUrl());
    }

    // ---------------- 并发隔离 ----------------

    @Test
    void concurrentTenantAccessKeepsThreadLocalIsolation() throws Exception {
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));
        int tasks = 12;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < tasks; i++) {
                boolean tenantA = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    TenantContext.set(new TenantContext.Session(
                            tenantA ? 1L : 2L, tenantA ? "tenant-a" : "tenant-b", 2,
                            tenantA ? "user-a" : "user-b"));
                    try {
                        start.await();
                        knowledgeBaseService.getOwned(9L);
                        return tenantA;
                    } catch (BizException e) {
                        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
                        return !tenantA;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> f : futures) {
                assertTrue(f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 构造辅助 ----------------

    private void session(String tenantId, int userType) {
        TenantContext.set(new TenantContext.Session(
                "tenant-a".equals(tenantId) ? 1L : 2L, tenantId, userType,
                "tenant-a".equals(tenantId) ? "user-a" : "user-b"));
    }

    private KnowledgeBaseEntity kb(String tenantId) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(9L);
        kb.setTenantId(tenantId);
        kb.setMilvusCollection("kb_9");
        kb.setEsIndex("kb_9");
        return kb;
    }

    private DocumentEntity doc(long id, long kbId, String tenantId) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setKbId(kbId);
        d.setTenantId(tenantId);
        d.setObjectKey(tenantId + "/" + kbId + "/a.txt");
        d.setStatus("READY");
        return d;
    }

    private ChunkEntity chunk(long id, long kbId, long documentId, String tenantId) {
        ChunkEntity c = new ChunkEntity();
        c.setId(id);
        c.setKbId(kbId);
        c.setDocumentId(documentId);
        c.setTenantId(tenantId);
        c.setSeq(1);
        c.setContent("内容");
        c.setPage(0);
        c.setStatus("AUTO");
        c.setChunkType("CHILD");
        return c;
    }

    private ChatSessionEntity chatSession(long id, String tenantId, long userId, long kbId) {
        ChatSessionEntity s = new ChatSessionEntity();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setUserId(userId);
        s.setKbId(kbId);
        return s;
    }
}
