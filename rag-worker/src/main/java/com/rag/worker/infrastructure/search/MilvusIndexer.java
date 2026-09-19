package com.rag.worker.infrastructure.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
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
    private volatile MilvusClientV2 client;

    public MilvusIndexer(@Value("${rag.milvus.host}") String host,
                         @Value("${rag.milvus.port}") int port) {
        this.host = host;
        this.port = port;
    }

    private MilvusClientV2 client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new MilvusClientV2(ConnectConfig.builder().uri("http://" + host + ":" + port).build());
                }
            }
        }
        return client;
    }

    public boolean hasCollection(String collection) {
        return client().hasCollection(HasCollectionReq.builder().collectionName(collection).build());
    }

    /** 幂等建集合；维度不一致时（空集合）重建。 */
    public void ensureCollection(String collection, int dim) {
        if (!hasCollection(collection)) {
            createCollection(collection, dim);
            return;
        }
        Integer existing = describeDim(collection);
        if (existing != null && existing != dim) {
            log.warn("Milvus 集合维度 {} != {}，重建空集合: {}", existing, dim, collection);
            dropCollection(collection);
            createCollection(collection, dim);
        }
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

    public void insert(String collection, List<long[]> ids, List<float[]> vectors) {
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
