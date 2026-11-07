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
    private final boolean allowAutoRecreate;
    private volatile MilvusClientV2 client;

    public MilvusClientWrapper(@Value("${rag.milvus.host}") String host,
                               @Value("${rag.milvus.port}") int port,
                               @Value("${rag.milvus.allow-auto-recreate:false}") boolean allowAutoRecreate) {
        this.host = host;
        this.port = port;
        this.allowAutoRecreate = allowAutoRecreate;
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
     * 幂等确保集合存在且向量维度匹配；维度不匹配且**确认**集合为空时重建，有数据或无法探测时报错。
     * 安全约束：探测失败必须 fail closed，禁止把"查询异常"当成"空集合"而删除数据。
     */
    public void ensureCollection(String collection, int dim) {
        if (!hasCollection(collection)) {
            createCollection(collection, dim);
            return;
        }
        Integer existing = describeDim(collection);
        if (existing == null) {
            // 无法确认现有维度时不做任何破坏性操作
            throw new BizException(ErrorCode.UPSTREAM,
                    "无法读取 Milvus 集合向量维度，已中止建集合操作: " + collection);
        }
        if (existing != dim) {
            long rows;
            try {
                rows = countRows(collection);
            } catch (Exception e) {
                throw new BizException(ErrorCode.UPSTREAM,
                        "Milvus 集合探测失败，无法确认是否为空，拒绝重建: " + collection + "（" + e.getMessage() + "）");
            }
            if (rows == 0) {
                if (!allowAutoRecreate) {
                    throw new BizException(ErrorCode.UPSTREAM,
                            "Milvus 集合维度不一致且为空，但自动重建已禁用（rag.milvus.allow-auto-recreate=false）："
                                    + collection + "。请手动删除集合或显式开启该配置后重试");
                }
                // 审计：任何 drop 都必须留下集合、维度与确认条件
                log.warn("[AUDIT] Milvus 重建空集合 collection={} oldDim={} newDim={} confirmedRows=0 reason=dimension-mismatch",
                        collection, existing, dim);
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

    /**
     * 探测集合是否有数据：limit(1) 有返回行即视为非空。
     * 查询异常必须向上抛出——调用方据此 fail closed，绝不能把异常当成 0 行。
     */
    private long countRows(String collection) {
        var resp = client().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                .collectionName(collection)
                .filter("")
                .outputFields(new ArrayList<>())
                .limit(1)
                .build());
        if (resp == null || resp.getQueryResults() == null) {
            throw new IllegalStateException("Milvus 集合行数探测返回空响应: " + collection);
        }
        return resp.getQueryResults().size();
    }

    /** 统计某文档在集合中的向量数（索引对账用，limit=expected+1 足以判定是否一致）。 */
    public long countByDocument(String collection, long documentId, int expected) {
        var resp = client().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                .collectionName(collection)
                .filter("document_id == " + documentId)
                .outputFields(List.of("chunk_id"))
                .limit(Math.max(1, expected + 1))
                .build());
        return resp == null || resp.getQueryResults() == null ? 0 : resp.getQueryResults().size();
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
