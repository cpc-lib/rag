package com.rag.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.llm.RerankClient;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** RAG-010：跨租户访问知识库必须返回 NOT_FOUND，不泄露资源存在性。 */
@ExtendWith(MockitoExtension.class)
class TenantIsolationTest {

    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private PipelineTaskMapper pipelineTaskMapper;
    @Mock
    private ModelService modelService;
    @Mock
    private MilvusClientWrapper milvus;
    @Mock
    private EsSearchClient es;
    @Mock
    private MinioStorage minio;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private UserManageService userManageService;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private FileLibraryService fileLibraryService;
    @Mock
    private LlmClient llmClient;
    @Mock
    private RerankClient rerankClient;
    @Mock
    private EtcdService etcdService;
    @Mock
    private MeterRegistry meterRegistry;

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;
    @InjectMocks
    private RetrievalService retrievalService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void crossTenantKnowledgeBaseIsReportedAsNotFound() {
        TenantContext.set(new TenantContext.Session(2L, "tenant-b", 2, "user-b"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class, () -> knowledgeBaseService.getOwned(9L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("知识库不存在", ex.getMessage());
    }

    @Test
    void crossTenantRetrievalIsReportedAsNotFound() {
        TenantContext.set(new TenantContext.Session(2L, "tenant-b", 2, "user-b"));
        when(kbMapper.selectById(9L)).thenReturn(kb("tenant-a"));

        BizException ex = assertThrows(BizException.class,
                () -> retrievalService.retrieve(TenantContext.require(), 9L, "问题"));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void sameTenantCanRead() {
        TenantContext.set(new TenantContext.Session(2L, "tenant-a", 2, "user-a"));
        KnowledgeBaseEntity kb = kb("tenant-a");
        when(kbMapper.selectById(9L)).thenReturn(kb);

        assertSame(kb, knowledgeBaseService.getOwned(9L));
    }

    @Test
    void platformAdminCanReadOtherTenant() {
        TenantContext.set(new TenantContext.Session(1L, "000000", 0, "admin"));
        KnowledgeBaseEntity kb = kb("tenant-a");
        when(kbMapper.selectById(9L)).thenReturn(kb);

        assertSame(kb, knowledgeBaseService.getOwned(9L));
    }

    private KnowledgeBaseEntity kb(String tenantId) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(9L);
        kb.setTenantId(tenantId);
        kb.setMilvusCollection("kb_9");
        kb.setEsIndex("kb_9");
        return kb;
    }
}
