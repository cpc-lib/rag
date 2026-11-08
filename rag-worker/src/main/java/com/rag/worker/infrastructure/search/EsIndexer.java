package com.rag.worker.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
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

    @Value("${rag.es.analyzer:cjk}")
    private String analyzer;

    public void ensureIndex(String index) {
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                log.info("ES 索引已存在（跳过创建）: {}；analyzer/mapping 变更需删除索引并重建文档", index);
                return;
            }
            // 与 rag-api 的 EsSearchClient 保持一致：content.exact 支持精确匹配，analyzer 默认 cjk
            String mapping = """
                    {"mappings":{"properties":{
                      "chunkId":{"type":"keyword"},
                      "documentId":{"type":"keyword"},
                      "kbId":{"type":"keyword"},
                      "fileName":{"type":"keyword"},
                      "page":{"type":"integer"},
                      "content":{"type":"text","analyzer":"%s",
                        "fields":{"exact":{"type":"keyword","ignore_above":256}}}
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
            // refresh=wait_for：bulk 返回时文档即可被检索/count 到，避免紧随其后的双写一致性校验误读为 0
            BulkRequest.Builder bulk = new BulkRequest.Builder().index(index).refresh(Refresh.WaitFor);
            for (EsDoc doc : docs) {
                bulk.operations(op -> op.index(idx -> idx
                        .index(index)
                        .id(String.valueOf(doc.chunkId()))
                        .document(doc)));
            }
            BulkResponse resp = client.bulk(bulk.build());
            if (resp.errors()) {
                // 部分 item 失败不能视为成功：必须抛出交给 MQ 重试，否则文档会被错误标记 READY
                List<String> failures = new ArrayList<>();
                for (var item : resp.items()) {
                    if (item.error() != null) {
                        failures.add("id=" + item.id() + " status=" + item.status()
                                + " reason=" + item.error().reason());
                    }
                }
                throw new IllegalStateException("ES bulk 部分写入失败 index=" + index
                        + " 失败=" + failures.size() + "/" + docs.size()
                        + " 首个原因: " + (failures.isEmpty() ? "unknown" : failures.get(0)));
            }
            log.info("ES bulk 写入完成 index={} 条数={}", index, docs.size());
        } catch (Exception e) {
            throw new IllegalStateException("ES 写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 仅删除该文档中不属于本次写入集合的陈旧 chunk。
     * 必须在 indexDocs 成功之后调用：写入失败时旧索引保持不变，检索仍可读；
     * 清理失败则抛错交由 MQ 重试，重试路径幂等收敛。
     */
    public void deleteStale(String index, long documentId, List<Long> keepIds) {
        if (keepIds.isEmpty()) {
            deleteByDocument(index, documentId);
            return;
        }
        try {
            List<FieldValue> keep = keepIds.stream()
                    .map(id -> FieldValue.of(String.valueOf(id)))
                    .toList();
            var resp = client.deleteByQuery(d -> d.index(index).refresh(true).query(q -> q.bool(b -> b
                    .filter(f -> f.term(t -> t.field("documentId").value(String.valueOf(documentId))))
                    .mustNot(mn -> mn.terms(t -> t.field("chunkId").terms(v -> v.value(keep)))))));
            log.info("ES 陈旧索引清理完成 index={} doc={} 删除={}条", index, documentId, resp.deleted());
        } catch (Exception e) {
            throw new IllegalStateException("ES 陈旧索引清理失败 index=" + index
                    + " doc=" + documentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 删除某文档旧索引。失败必须向上抛出：旧切片残留会导致重建后 ES 中出现重复/孤儿索引，
     * 需由 MQ 重试重新执行删除与写入。
     */
    public void deleteByDocument(String index, long documentId) {
        try {
            client.deleteByQuery(d -> d.index(index).refresh(true)
                    .query(q -> q.term(t -> t.field("documentId").value(String.valueOf(documentId)))));
            log.info("ES 删除文档旧索引完成 index={} doc={}", index, documentId);
        } catch (Exception e) {
            throw new IllegalStateException("ES 删除文档旧索引失败 index=" + index
                    + " doc=" + documentId + ": " + e.getMessage(), e);
        }
    }

    /** 统计某文档在 ES 中的切片数（写入后一致性校验用）。 */
    public long countByDocument(String index, long documentId) {
        try {
            var resp = client.count(c -> c.index(index)
                    .query(q -> q.term(t -> t.field("documentId").value(String.valueOf(documentId)))));
            return resp.count();
        } catch (Exception e) {
            throw new IllegalStateException("ES 统计文档切片数失败 index=" + index
                    + " doc=" + documentId + ": " + e.getMessage(), e);
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
