package com.rag.api.infrastructure.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.RetryConfig;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus v2 SDK 封装：按 KB 粒度建集合 kb_{kbId}，schema 固定为
 * chunk_id(Int64 PK) / document_id(Int64) / vector(FloatVector, COSINE)。
 */
@Slf4j
@Component
public class MilvusClientWrapper {

    public record MilvusHit(long chunkId, long documentId, float score) {
    }

    private final String host;
    private final int port;
    private volatile MilvusClientV2 client;

    public MilvusClientWrapper(@Value("${rag.milvus.host}") String host,
                               @Value("${rag.milvus.port}") int port) {
        this.host = host;
        this.port = port;
    }

    private MilvusClientV2 client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = createClient();
                }
            }
        }
        return client;
    }

    private MilvusClientV2 createClient() {
        // SDK 默认重试 75 次且总时长无上限，Milvus 宕机时调用线程会被挂住数分钟。
        // 仅收紧重试：建连探测 3s 超时、最多重试 2 次（退避 100ms~1s），失败由上层降级（向量召回失败时仅走 ES）。
        // 注意：不要设置 rpcDeadlineMs——SDK 2.4.3 在 rpcDeadlineMs>0 时会同时 withWaitForReady()，
        // 宕机期间 RPC 在 gRPC 延迟队列里停车等待重连，恢复后携带早已过期的 deadline 重放，
        // 报 "ClientCall started after CallOptions deadline was exceeded -N seconds" 且不可重试。
        MilvusClientV2 c = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .connectTimeoutMs(3000)
                .build());
        c.retryConfig(RetryConfig.builder()
                .maxRetryTimes(2)
                .initialBackOffMs(100)
                .maxBackOffMs(1000)
                .build());
        log.info("Milvus 客户端已初始化: {}:{}（connectTimeout=3s, maxRetry=2，不设 rpcDeadline 避免 wait-for-ready 停车）", host, port);
        return c;
    }

    public boolean hasCollection(String collection) {
        Boolean exists = client().hasCollection(HasCollectionReq.builder().collectionName(collection).build());
        // SDK 2.4.3 在 RPC 失败且重试耗尽时返回 null（吞掉底层异常），自动拆箱会 NPE 掩盖真实原因，转为显式异常
        if (exists == null) {
            throw new IllegalStateException("Milvus hasCollection 返回 null（通常为连接不可达/重试耗尽）: " + collection);
        }
        return exists;
    }

    /**
     * 幂等确保集合存在且向量维度匹配；维度不匹配且集合为空时重建，有数据则报错提示租户管理员。
     */
    public void ensureCollection(String collection, int dim) {
        if (!hasCollection(collection)) {
            createCollection(collection, dim);
            return;
        }
        Integer existing = describeDim(collection);
        if (existing != null && existing != dim) {
            if (countRows(collection) == 0) {
                log.warn("Milvus 集合维度 {} != {}，重建: {}", existing, dim, collection);
                dropCollection(collection);
                createCollection(collection, dim);
            } else {
                throw new BizException(ErrorCode.UPSTREAM,
                        "向量维度与已有数据不一致（集合维度 " + existing + "，模型维度 " + dim + "），请更换知识库或模型配置");
            }
            return;
        }
        log.info("Milvus 集合已存在（跳过创建）: {}", collection);
    }

    private void createCollection(String collection, int dim) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("chunk_id").dataType(io.milvus.v2.common.DataType.Int64)
                .isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("document_id").dataType(io.milvus.v2.common.DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("vector").dataType(io.milvus.v2.common.DataType.FloatVector)
                .dimension(dim).build());
        IndexParam indexParam = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
        client().createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .build());
        client().loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        log.info("Milvus 集合已创建并加载: {} dim={}", collection, dim);
    }

    private Integer describeDim(String collection) {
        try {
            var resp = client().describeCollection(
                    io.milvus.v2.service.collection.request.DescribeCollectionReq.builder().collectionName(collection).build());
            if (resp == null || resp.getCollectionSchema() == null) {
                return null;
            }
            for (CreateCollectionReq.FieldSchema field : resp.getCollectionSchema().getFieldSchemaList()) {
                if ("vector".equals(field.getName())) {
                    return field.getDimension();
                }
            }
        } catch (Exception e) {
            log.warn("Milvus describeCollection 失败: {}", e.getMessage());
        }
        return null;
    }

    private long countRows(String collection) {
        try {
            var resp = client().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collection)
                    .filter("")
                    .outputFields(new ArrayList<>())
                    .limit(1)
                    .build());
            // limit(1) 探测：有返回行即视为集合非空
            return resp == null || resp.getQueryResults() == null ? 0 : resp.getQueryResults().size();
        } catch (Exception e) {
            return 0;
        }
    }

    public void insert(String collection, List<long[]> ids, List<float[]> vectors) {
        List<JsonObject> rows = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            JsonObject row = new JsonObject();
            row.add("chunk_id", new JsonPrimitive(ids.get(i)[0]));
            row.add("document_id", new JsonPrimitive(ids.get(i)[1]));
            com.google.gson.JsonArray vec = new com.google.gson.JsonArray(vectors.get(i).length);
            for (float v : vectors.get(i)) {
                vec.add(v);
            }
            row.add("vector", vec);
            rows.add(row);
        }
        client().insert(InsertReq.builder().collectionName(collection).data(rows).build());
    }

    public void deleteByDocument(String collection, long documentId) {
        try {
            client().delete(DeleteReq.builder().collectionName(collection)
                    .filter("document_id == " + documentId).build());
        } catch (Exception e) {
            log.warn("Milvus 删除向量失败 collection={} doc={}: {}", collection, documentId, e.getMessage());
        }
    }

    public List<MilvusHit> search(String collection, float[] vector, int topK) {
        SearchResp resp = client().search(SearchReq.builder()
                .collectionName(collection)
                .data(List.of(new FloatVec(vector)))
                .topK(topK)
                .outputFields(List.of("document_id"))
                .build());
        List<MilvusHit> hits = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return hits;
        }
        for (SearchResp.SearchResult r : resp.getSearchResults().get(0)) {
            long chunkId = asLong(r.getId());
            long docId = 0;
            Object doc = r.getEntity() != null ? r.getEntity().get("document_id") : null;
            if (doc != null) {
                docId = asLong(doc);
            }
            hits.add(new MilvusHit(chunkId, docId, r.getScore()));
        }
        return hits;
    }

    private long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof JsonPrimitive p) {
            return p.getAsLong();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    public void dropCollection(String collection) {
        client().dropCollection(DropCollectionReq.builder().collectionName(collection).build());
    }

    @PreDestroy
    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
        }
    }
}
