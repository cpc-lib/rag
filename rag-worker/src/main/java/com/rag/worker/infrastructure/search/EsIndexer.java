package com.rag.worker.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;

/**
 * ES 索引端：建索引 / 删旧 / bulk 写入（BM25 侧数据）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexer {

    public record EsDoc(long chunkId, long documentId, long kbId, String fileName, int page, String content) {
    }

    private final ElasticsearchClient client;

    @Value("${rag.es.analyzer:standard}")
    private String analyzer;

    public void ensureIndex(String index) {
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                log.info("ES 索引已存在（跳过创建）: {}", index);
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
            throw new IllegalStateException("ES 索引创建失败: " + e.getMessage(), e);
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
            } else {
                log.info("ES bulk 写入完成 index={} 条数={}", index, docs.size());
            }
        } catch (Exception e) {
            throw new IllegalStateException("ES 写入失败: " + e.getMessage(), e);
        }
    }

    public void deleteByDocument(String index, long documentId) {
        try {
            client.deleteByQuery(d -> d.index(index)
                    .query(q -> q.term(t -> t.field("documentId").value(String.valueOf(documentId)))));
            log.info("ES 删除文档旧索引完成 index={} doc={}", index, documentId);
        } catch (Exception e) {
            log.warn("ES 删除旧索引失败 index={} doc={}: {}", index, documentId, e.getMessage());
        }
    }

    public void deleteIndex(String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (Exception e) {
            log.warn("ES 删除索引失败: {}", index, e);
        }
    }
}
