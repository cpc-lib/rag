package com.rag.api.infrastructure.search;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 封装：按 KB 粒度建索引 kb_{kbId}，BM25 检索。
 */
@Slf4j
@Component
public class EsSearchClient {

    public record EsDoc(long chunkId, long documentId, long kbId, String fileName, int page, String content) {
    }

    private final ElasticsearchClient client;
    private final String analyzer;

    public EsSearchClient(ElasticsearchClient client,
                          @Value("${rag.es.analyzer:standard}") String analyzer) {
        this.client = client;
        this.analyzer = analyzer;
    }

    public void ensureIndex(String index) {
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                return;
            }
            String mapping = """
                    {"mappings":{"properties":{
                      "chunkId":{"type":"keyword"},
                      "documentId":{"type":"keyword"},
                      "kbId":{"type":"keyword"},
                      "fileName":{"type":"keyword"},
                      "page":{"type":"integer"},
                      "content":{"type":"text","analyzer":"%s"}
                    }}}
                    """.formatted(analyzer);
            client.indices().create(c -> c.index(index).withJson(new StringReader(mapping)));
            log.info("ES 索引已创建: {} analyzer={}", index, analyzer);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "ES 索引创建失败: " + e.getMessage());
        }
    }

    public void indexDocs(String index, List<EsDoc> docs) {
        if (docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().index(index);
            for (EsDoc doc : docs) {
                bulk.operations(op -> op.index(idx -> idx
                        .index(index)
                        .id(String.valueOf(doc.chunkId()))
                        .document(doc)));
            }
            BulkResponse resp = client.bulk(bulk.build());
            if (resp.errors()) {
                log.warn("ES bulk 存在部分错误 index={} size={}", index, docs.size());
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "ES 写入失败: " + e.getMessage());
        }
    }

    public void deleteByDocument(String index, long documentId) {
        try {
            client.deleteByQuery(d -> d.index(index)
                    .query(q -> q.term(t -> t.field("documentId").value(String.valueOf(documentId)))));
        } catch (Exception e) {
            log.warn("ES 删除文档向量失败 index={} doc={}: {}", index, documentId, e.getMessage());
        }
    }

    /** BM25 关键词检索，返回按相关度排序的 chunkId。 */
    public List<Long> search(String index, String keyword, int topK) {
        try {
            SearchResponse<EsDoc> resp = client.search(s -> s.index(index)
                            .query(q -> q.match(m -> m.field("content").query(keyword)))
                            .size(topK),
                    EsDoc.class);
            List<Long> ids = new ArrayList<>();
            for (var hit : resp.hits().hits()) {
                if (hit.source() != null) {
                    ids.add(hit.source().chunkId());
                }
            }
            return ids;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "ES 检索失败: " + e.getMessage());
        }
    }

    public void deleteIndex(String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (Exception e) {
            log.warn("ES 删除索引失败: {}", index, e);
        }
    }

    @PreDestroy
    public void close() {
        try {
            client._transport().close();
        } catch (Exception ignored) {
        }
    }
}
