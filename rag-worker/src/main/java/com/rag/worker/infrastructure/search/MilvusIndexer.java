package com.rag.worker.infrastructure.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import io.milvus.v2.service.utility.request.AlterAliasReq;
import io.milvus.v2.service.utility.request.CreateAliasReq;
import io.milvus.v2.service.utility.request.DescribeAliasReq;
import com.rag.api.infrastructure.search.IndexNames;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 索引端：建集合 / 删旧向量 / 写入向量（COSINE）。
 */
@Slf4j
@Component
public class MilvusIndexer {

    private final String host;
    private final int port;
    private final boolean allowAutoRecreate;
    private volatile MilvusClientV2 client;

    public MilvusIndexer(@Value("${rag.milvus.host}") String host,
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
        // SDK 默认重试 75 次且总时长无上限，Milvus 宕机时索引阶段会长时间挂住消费线程。
        // 仅收紧重试：建连探测 3s 超时、最多重试 2 次（退避 100ms~1s），失败后交由 MQ 消费侧重试/DLQ 处理。
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
     * 幂等建集合；维度不一致时仅在**确认集合为空**后重建。
     * 安全约束：维度/行数探测失败必须 fail closed，禁止把查询异常误判为空集合而删除数据。
     */
    public void ensureCollection(String collection, int dim) {
        if (!hasCollection(collection)) {
            createCollection(collection, dim);
            return;
        }
        Integer existing = describeDim(collection);
        if (existing == null) {
            throw new IllegalStateException("无法读取 Milvus 集合向量维度，已中止建集合操作: " + collection);
        }
        if (existing != dim) {
            long rows;
            try {
                rows = countRows(collection);
            } catch (Exception e) {
                throw new IllegalStateException("Milvus 集合探测失败，无法确认是否为空，拒绝重建: "
                        + collection + "（" + e.getMessage() + "）", e);
            }
            if (rows == 0) {
                if (!allowAutoRecreate) {
                    throw new IllegalStateException("Milvus 集合维度不一致且为空，但自动重建已禁用"
                            + "（rag.milvus.allow-auto-recreate=false）：" + collection
                            + "。请手动删除集合或显式开启该配置后重试");
                }
                // 审计：任何 drop 都必须留下集合、维度与确认条件
                log.warn("[AUDIT] Milvus 重建空集合 collection={} oldDim={} newDim={} confirmedRows=0 reason=dimension-mismatch",
                        collection, existing, dim);
                dropCollection(collection);
                createCollection(collection, dim);
            } else {
                throw new IllegalStateException("向量维度与已有数据不一致（集合维度 " + existing
                        + "，模型维度 " + dim + "），请更换知识库或模型配置");
            }
            return;
        }
        log.info("Milvus 集合已存在（跳过创建）: {}", collection);
    }

    /** 探测集合是否有数据；查询异常向上抛出，由调用方 fail closed。 */
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

    /** 统计某文档在集合中的向量数（写入后一致性校验用，limit=expected+1 足以判定是否一致）。 */
    public long countByDocument(String collection, long documentId, int expected) {
        var resp = client().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                .collectionName(collection)
                .filter("document_id == " + documentId)
                .outputFields(List.of("chunk_id"))
                .limit(Math.max(1, expected + 1))
                .build());
        return resp == null || resp.getQueryResults() == null ? 0 : resp.getQueryResults().size();
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
        log.info("Milvus 集合已创建: {} dim={}", collection, dim);
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
     * 单批写入上限：1024 维单向量约 4KB，500 条约 2MB，
     * 避免一次性写入过多向量超过 gRPC 默认 64MB 消息上限（RESOURCE_EXHAUSTED）。
     */
    private static final int INSERT_BATCH = 500;

    /**
     * 按 chunk_id 主键 upsert：重建索引时切片 ID 不变，普通 insert 会产生重复主键行，
     * 导致 countByDocument 翻倍并让检索出现重复命中。
     */
    public void upsert(String collection, List<long[]> ids, List<float[]> vectors) {
        List<JsonObject> rows = buildRows(ids, vectors);
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            int to = Math.min(from + INSERT_BATCH, rows.size());
            client().upsert(io.milvus.v2.service.vector.request.UpsertReq.builder()
                    .collectionName(collection)
                    .data(rows.subList(from, to)).build());
        }
        log.info("Milvus 向量 upsert 完成 collection={} 条数={}", collection, rows.size());
    }

    private List<JsonObject> buildRows(List<long[]> ids, List<float[]> vectors) {
        List<JsonObject> rows = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            JsonObject row = new JsonObject();
            row.add("chunk_id", new JsonPrimitive(ids.get(i)[0]));
            row.add("document_id", new JsonPrimitive(ids.get(i)[1]));
            JsonArray vec = new JsonArray(vectors.get(i).length);
            for (float v : vectors.get(i)) {
                vec.add(v);
            }
            row.add("vector", vec);
            rows.add(row);
        }
        return rows;
    }

    /** Milvus query 单次返回上限，超过该值需要分页；单文档切片数达到该量级会告警。 */
    private static final int QUERY_LIMIT = 16384;
    private static final int DELETE_BATCH = 500;

    /**
     * 删除该文档中不属于本次写入集合的陈旧向量。
     * 必须在 upsert 成功之后调用：写入失败时旧向量保持不变，索引仍可读；
     * 清理失败则抛错交由 MQ 重试，重试路径幂等收敛。
     */
    public void deleteStale(String collection, long documentId, List<Long> keepIds) {
        if (keepIds.isEmpty()) {
            deleteByDocument(collection, documentId);
            return;
        }
        java.util.Set<Long> keep = new java.util.HashSet<>(keepIds);
        List<Long> stale = new ArrayList<>();
        try {
            var resp = client().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collection)
                    .filter("document_id == " + documentId)
                    .outputFields(List.of("chunk_id"))
                    .limit(QUERY_LIMIT)
                    .build());
            if (resp != null && resp.getQueryResults() != null) {
                for (var result : resp.getQueryResults()) {
                    Object value = result.getEntity() == null ? null : result.getEntity().get("chunk_id");
                    if (value instanceof Number n && !keep.contains(n.longValue())) {
                        stale.add(n.longValue());
                    }
                }
                if (resp.getQueryResults().size() >= QUERY_LIMIT) {
                    log.warn("Milvus 旧向量查询达到单次上限，可能存在未清理的陈旧索引 collection={} doc={} limit={}",
                            collection, documentId, QUERY_LIMIT);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 查询旧向量失败，已中止陈旧索引清理 collection="
                    + collection + " doc=" + documentId + ": " + e.getMessage(), e);
        }
        if (stale.isEmpty()) {
            log.info("Milvus 无陈旧向量需要清理 collection={} doc={}", collection, documentId);
            return;
        }
        try {
            for (int from = 0; from < stale.size(); from += DELETE_BATCH) {
                int to = Math.min(from + DELETE_BATCH, stale.size());
                String ids = stale.subList(from, to).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
                client().delete(DeleteReq.builder().collectionName(collection)
                        .filter("chunk_id in [" + ids + "]").build());
            }
            log.info("Milvus 陈旧向量清理完成 collection={} doc={} 删除={}条",
                    collection, documentId, stale.size());
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 陈旧向量清理失败 collection=" + collection
                    + " doc=" + documentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 删除某文档的全部向量。失败必须向上抛出：旧向量残留会与新向量并存，
     * 导致重建后检索结果重复，必须由重试机制重新执行删除。
     */
    public void deleteByDocument(String collection, long documentId) {
        try {
            client().delete(DeleteReq.builder().collectionName(collection)
                    .filter("document_id == " + documentId).build());
            log.info("Milvus 删除文档向量完成 collection={} doc={}", collection, documentId);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 删除文档向量失败 collection=" + collection
                    + " doc=" + documentId + ": " + e.getMessage(), e);
        }
    }

    public void dropCollection(String collection) {
        client().dropCollection(DropCollectionReq.builder().collectionName(collection).build());
    }

    // ---------- 别名级双版本（RAG-003） ----------

    /** 读别名当前是否指向目标集合。 */
    public boolean aliasPointsTo(String base, String target) {
        return target.equals(aliasTarget(IndexNames.alias(base)));
    }

    /** 幂等确保读别名指向目标集合；已存在则原子改指。 */
    public void switchAlias(String base, String target) {
        String alias = IndexNames.alias(base);
        try {
            String current = aliasTarget(alias);
            if (target.equals(current)) {
                return;
            }
            if (current == null) {
                client().createAlias(CreateAliasReq.builder().alias(alias).collectionName(target).build());
            } else {
                client().alterAlias(AlterAliasReq.builder().alias(alias).collectionName(target).build());
            }
            log.info("Milvus 读别名已切换 alias={} -> {}（原指向 {}）", alias, target, current);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 别名切换失败: " + e.getMessage(), e);
        }
    }

    private String aliasTarget(String alias) {
        try {
            var resp = client().describeAlias(DescribeAliasReq.builder().alias(alias).build());
            return resp == null ? null : resp.getCollectionName();
        } catch (Exception e) {
            return null;
        }
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
