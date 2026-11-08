package com.rag.api.e2e;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.rag.api.application.ModelService;
import com.rag.api.application.RetrievalService;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.llm.RerankClient;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG-010 真实中间件端到端隔离测试：
 * 使用真实 MySQL（MyBatis mapper）、Elasticsearch、Milvus 走生产 {@link RetrievalService#retrieve}，
 * 验证「知识库 / 文档 / 切片」三层租户隔离，以及索引中即便存在其他租户的 chunk id 也不会被引用。
 *
 * <p>默认不参与常规构建，需显式开启并保证本地中间件已启动：</p>
 * <pre>
 * docker compose up -d mysql elasticsearch milvus
 * mvn -f rag-api/pom.xml test -Dtest=TenantIsolationE2ETest -DRAG_E2E=true
 * </pre>
 *
 * <p>连接参数可用环境变量覆盖：{@code RAG_E2E_MYSQL_URL/USER/PASSWORD}、
 * {@code RAG_E2E_ES_HOST/PORT}、{@code RAG_E2E_MILVUS_HOST/PORT}。</p>
 */
@EnabledIfSystemProperty(named = "RAG_E2E", matches = "true")
class TenantIsolationE2ETest {

    private static final int VECTOR_DIM = 8;
    private static final float[] QUERY_VECTOR = {1, 0, 0, 0, 0, 0, 0, 0};
    private static final String QUERY = "租户隔离端到端验证关键词";

    @Test
    void retrievalIsIsolatedAcrossRealMysqlEsAndMilvus() throws Exception {
        String suffix = String.format("%06d", System.nanoTime() % 1_000_000);
        String tenantA = "E2EA" + suffix;
        String tenantB = "E2EB" + suffix;

        String mysqlUrl = env("RAG_E2E_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/rag?serverTimezone=Asia/Shanghai"
                        + "&useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(mysqlUrl);
        hikari.setUsername(env("RAG_E2E_MYSQL_USER", "root"));
        hikari.setPassword(env("RAG_E2E_MYSQL_PASSWORD", "rag123456"));
        hikari.setMaximumPoolSize(2);
        HikariDataSource dataSource = new HikariDataSource(hikari);

        RestClient restClient = RestClient.builder(new HttpHost(
                env("RAG_E2E_ES_HOST", "127.0.0.1"),
                Integer.parseInt(env("RAG_E2E_ES_PORT", "9200")), "http")).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);
        EsSearchClient es = new EsSearchClient(esClient, "cjk");
        MilvusClientWrapper milvus = new MilvusClientWrapper(
                env("RAG_E2E_MILVUS_HOST", "127.0.0.1"),
                Integer.parseInt(env("RAG_E2E_MILVUS_PORT", "19530")), false);

        Long kbAId = null;
        Long kbBId = null;
        String indexA = null;
        String indexB = null;
        try (SqlSession session = buildMyBatis(dataSource).openSession(true)) {
            KnowledgeBaseMapper kbMapper = session.getMapper(KnowledgeBaseMapper.class);
            DocumentMapper documentMapper = session.getMapper(DocumentMapper.class);
            ChunkMapper chunkMapper = session.getMapper(ChunkMapper.class);

            KnowledgeBaseEntity kbA = newKb(kbMapper, tenantA, "E2E-A");
            KnowledgeBaseEntity kbB = newKb(kbMapper, tenantB, "E2E-B");
            kbAId = kbA.getId();
            kbBId = kbB.getId();
            indexA = kbA.getEsIndex();
            indexB = kbB.getEsIndex();

            DocumentEntity docA = newDoc(documentMapper, kbA, "a.md", "e2e/a.md");
            DocumentEntity docB = newDoc(documentMapper, kbB, "b.md", "e2e/b.md");
            ChunkEntity chunkA = newChunk(chunkMapper, kbA, docA, "租户 A 的私有内容：" + QUERY);
            ChunkEntity chunkB = newChunk(chunkMapper, kbB, docB, "租户 B 的私有内容：" + QUERY);

            es.ensureIndex(indexA);
            es.ensureIndex(indexB);
            // 故意把 B 的 chunk 写入 A 的索引，模拟索引残留；检索侧必须按 kb/tenant 过滤掉
            es.indexDocs(indexA, List.of(
                    new EsSearchClient.EsDoc(chunkA.getId(), docA.getId(), kbA.getId(), docA.getFileName(), 0, chunkA.getContent()),
                    new EsSearchClient.EsDoc(chunkB.getId(), docB.getId(), kbB.getId(), docB.getFileName(), 0, chunkB.getContent())));
            es.indexDocs(indexB, List.of(
                    new EsSearchClient.EsDoc(chunkB.getId(), docB.getId(), kbB.getId(), docB.getFileName(), 0, chunkB.getContent())));
            String refreshA = indexA;
            String refreshB = indexB;
            esClient.indices().refresh(r -> r.index(refreshA, refreshB));

            milvus.ensureCollection(kbA.getMilvusCollection(), VECTOR_DIM);
            milvus.ensureCollection(kbB.getMilvusCollection(), VECTOR_DIM);
            milvus.insert(kbA.getMilvusCollection(),
                    List.of(new long[]{chunkA.getId(), docA.getId()}, new long[]{chunkB.getId(), docB.getId()}),
                    List.of(QUERY_VECTOR, QUERY_VECTOR));
            milvus.insert(kbB.getMilvusCollection(),
                    List.of(new long[]{chunkB.getId(), docB.getId()}), List.of(QUERY_VECTOR));

            // 索引层确实同时包含两个租户的 chunk，证明后续隔离来自检索侧过滤
            assertTrue(es.search(indexA, QUERY, 10).contains(chunkB.getId()), "测试前置：A 索引应包含 B 的残留 chunk");

            RetrievalService retrieval = retrievalService(kbMapper, documentMapper, chunkMapper, milvus, es);

            RetrievalService.RetrievalResult a = retrieval.retrieve(
                    new TenantContext.Session(1L, tenantA, 2, "e2e-a"), kbA.getId(), QUERY);
            assertEquals(1, a.citations().size(), "租户 A 只能引用自己的切片");
            assertEquals(chunkA.getId(), a.citations().get(0).chunkId());
            assertEquals(docA.getId(), a.citations().get(0).documentId());
            assertTrue(a.vectorAvailable() && a.keywordAvailable(), "真实 ES/Milvus 双通道均应可用");

            RetrievalService.RetrievalResult b = retrieval.retrieve(
                    new TenantContext.Session(2L, tenantB, 2, "e2e-b"), kbB.getId(), QUERY);
            assertEquals(chunkB.getId(), b.citations().get(0).chunkId());

            BizException denied = assertThrows(BizException.class, () -> retrieval.retrieve(
                    new TenantContext.Session(2L, tenantB, 2, "e2e-b"), kbA.getId(), QUERY));
            assertEquals(ErrorCode.NOT_FOUND, denied.getErrorCode(), "跨租户知识库必须返回不存在");
        } finally {
            if (indexA != null) {
                deleteIndex(esClient, indexA);
            }
            if (indexB != null) {
                deleteIndex(esClient, indexB);
            }
            if (kbAId != null) {
                cleanupCollection(milvus, kbAId);
            }
            if (kbBId != null) {
                cleanupCollection(milvus, kbBId);
                cleanupDatabase(dataSource, kbBId);
            }
            if (kbAId != null) {
                cleanupDatabase(dataSource, kbAId);
            }
            milvus.close();
            transport.close();
            restClient.close();
            dataSource.close();
        }
    }

    private static RetrievalService retrievalService(KnowledgeBaseMapper kbMapper, DocumentMapper documentMapper,
                                                     ChunkMapper chunkMapper, MilvusClientWrapper milvus,
                                                     EsSearchClient es) {
        ModelService modelService = mock(ModelService.class);
        ModelEntity embedding = new ModelEntity();
        embedding.setBaseUrl("http://e2e-embedding");
        embedding.setApiKey("e2e-key");
        embedding.setModel("e2e-embedding");
        when(modelService.findEnabled(anyString(), org.mockito.ArgumentMatchers.eq(ModelService.EMBEDDING)))
                .thenReturn(embedding);

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.embed(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.<String>anyList()))
                .thenReturn(List.of(QUERY_VECTOR));

        EtcdService etcd = mock(EtcdService.class);
        when(etcd.retrievalConfig()).thenReturn(new EtcdService.RetrievalConfig());

        MinioStorage minio = mock(MinioStorage.class);
        when(minio.presignUrl(anyString())).thenReturn("http://minio/e2e");

        return new RetrievalService(kbMapper, modelService, chunkMapper, documentMapper, llmClient,
                mock(RerankClient.class), milvus, es, minio, etcd, new SimpleMeterRegistry());
    }

    private static SqlSessionFactory buildMyBatis(HikariDataSource dataSource) throws Exception {
        Environment environment = new Environment("rag-e2e", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/KnowledgeBaseMapper.xml", "mapper/DocumentMapper.xml",
                "mapper/ChunkMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static KnowledgeBaseEntity newKb(KnowledgeBaseMapper mapper, String tenantId, String name) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId(tenantId);
        kb.setName(name);
        kb.setChunkStrategy("AUTO");
        mapper.insert(kb);
        kb.setMilvusCollection("kb_" + kb.getId());
        kb.setEsIndex("kb_" + kb.getId());
        mapper.updateById(kb);
        return kb;
    }

    private static DocumentEntity newDoc(DocumentMapper mapper, KnowledgeBaseEntity kb, String name, String key) {
        DocumentEntity doc = new DocumentEntity();
        doc.setKbId(kb.getId());
        doc.setTenantId(kb.getTenantId());
        doc.setFileName(name);
        doc.setObjectKey(key);
        doc.setFileSize(1L);
        doc.setStatus("READY");
        doc.setProgress(100);
        doc.setPageCount(1);
        mapper.insert(doc);
        return doc;
    }

    private static ChunkEntity newChunk(ChunkMapper mapper, KnowledgeBaseEntity kb, DocumentEntity doc, String content) {
        ChunkEntity chunk = new ChunkEntity();
        chunk.setKbId(kb.getId());
        chunk.setDocumentId(doc.getId());
        chunk.setTenantId(kb.getTenantId());
        chunk.setSeq(1);
        chunk.setContent(content);
        chunk.setPage(0);
        chunk.setStatus("AUTO");
        chunk.setChunkType("CHILD");
        chunk.setContentType("TEXT");
        mapper.insert(chunk);
        return chunk;
    }

    private static void deleteIndex(ElasticsearchClient client, String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (Exception ignored) {
            // 清理失败不影响断言结果
        }
    }

    private static void cleanupCollection(MilvusClientWrapper milvus, long kbId) {
        try {
            milvus.dropCollection("kb_" + kbId);
        } catch (Exception ignored) {
            // 清理失败不影响断言结果
        }
    }

    private static void cleanupDatabase(HikariDataSource dataSource, long kbId) {
        try (Connection connection = dataSource.getConnection()) {
            for (String sql : List.of("DELETE FROM chunk WHERE kb_id = ?",
                    "DELETE FROM document WHERE kb_id = ?", "DELETE FROM knowledge_base WHERE id = ?")) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, kbId);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响断言结果
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
