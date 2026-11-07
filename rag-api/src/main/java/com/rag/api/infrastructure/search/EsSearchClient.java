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
                          @Value("${rag.es.analyzer:cjk}") String analyzer) {
        this.client = client;
        this.analyzer = analyzer;
    }

    public void ensureIndex(String index) {
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                log.info("ES 索引已存在（跳过创建）: {}；analyzer/mapping 变更需删除索引并重建文档", index);
                return;
            }
            // content.exact 用于订单号/接口名/错误码等精确匹配；analyzer 默认 cjk（内置中文二元分词）
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
                // 部分 item 失败不能视为成功，否则调用方会把不完整索引当作已就绪
                List<String> failures = new ArrayList<>();
                for (var item : resp.items()) {
                    if (item.error() != null) {
                        failures.add("id=" + item.id() + " status=" + item.status()
                                + " reason=" + item.error().reason());
                    }
                }
                log.error("ES bulk 部分写入失败 index={} 失败={}/{} 首个原因: {}",
                        index, failures.size(), docs.size(), failures.isEmpty() ? "unknown" : failures.get(0));
                throw new BizException(ErrorCode.UPSTREAM, "ES 部分写入失败（" + failures.size()
                        + "/" + docs.size() + "）: " + (failures.isEmpty() ? "unknown" : failures.get(0)));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "ES 写入失败: " + e.getMessage());
        }
    }

    /** 统计某文档在 ES 中的切片数（索引对账用）。 */
    public long countByDocument(String index, long documentId) {
        try {
            var resp = client.count(c -> c.index(index)
                    .query(q -> q.term(t -> t.field("documentId").value(String.valueOf(documentId)))));
            return resp.count();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "ES 统计文档切片数失败: " + e.getMessage());
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

    /**
     * BM25 关键词检索，返回按相关度排序的 chunkId。
     * 正文分词匹配 + 精确子字段加权：订单号/接口名等整串命中时优先；
     * 只回传 chunkId、关闭 total 统计，降低大索引下的网络与计数开销。
     */
    public List<Long> search(String index, String keyword, int topK) {
        try {
            SearchResponse<EsDoc> resp = client.search(s -> s.index(index)
                            .query(q -> q.bool(b -> b
                                    .should(sh -> sh.match(m -> m.field("content").query(keyword)))
                                    .should(sh -> sh.term(t -> t.field("content.exact")
                                            .value(keyword).boost(2.0f)))
                                    .minimumShouldMatch("1")))
                            .trackTotalHits(t -> t.enabled(false))
                            .source(src -> src.filter(f -> f.includes("chunkId")))
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
            // 先 exists 再删：索引可能从未创建过（建库时预创建失败/文档未入库），直接删会抛 index_not_found
            if (client.indices().exists(e -> e.index(index)).value()) {
                client.indices().delete(d -> d.index(index));
            }
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
