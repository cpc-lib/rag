package com.rag.api.infrastructure.etcd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * etcd：服务注册（租约 15s keepalive）+ 检索动态配置（Watch 前缀热刷新）。
 * etcd 不可用时全部降级为本地默认值，不阻塞启动。
 */
@Slf4j
@Service
public class EtcdService {

    public static class RetrievalConfig {
        public int vectorTopN = 20;
        public int keywordTopN = 20;
        public int topK = 8;
        public int rrfK = 60;
        public double lowConfidenceThreshold = 0.35;
    }

    private final String endpoints;
    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final RetrievalConfig defaults = new RetrievalConfig();

    private volatile Client client;
    private volatile boolean available = false;
    private volatile RetrievalConfig retrievalConfig = new RetrievalConfig();
    private volatile io.etcd.jetcd.support.CloseableClient keepAliveClient;

    public EtcdService(@Value("${rag.etcd.endpoints}") String endpoints,
                       @Value("${rag.etcd.enabled:true}") boolean enabled,
                       @Value("${rag.retrieval.vector-top-n:20}") int vectorTopN,
                       @Value("${rag.retrieval.keyword-top-n:20}") int keywordTopN,
                       @Value("${rag.retrieval.top-k:8}") int topK,
                       @Value("${rag.retrieval.rrf-k:60}") int rrfK,
                       @Value("${rag.retrieval.low-confidence-threshold:0.35}") double threshold,
                       ObjectMapper objectMapper) {
        this.endpoints = endpoints;
        this.enabled = enabled;
        this.objectMapper = objectMapper;
        this.retrievalConfig = defaults(vectorTopN, keywordTopN, topK, rrfK, threshold);
    }

    private RetrievalConfig defaults(int v, int k, int tk, int rrf, double th) {
        RetrievalConfig c = new RetrievalConfig();
        c.vectorTopN = v;
        c.keywordTopN = k;
        c.topK = tk;
        c.rrfK = rrf;
        c.lowConfidenceThreshold = th;
        return c;
    }

    public RetrievalConfig retrievalConfig() {
        return retrievalConfig;
    }

    public boolean isAvailable() {
        return available;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        try {
            client = Client.builder().endpoints(endpoints.split(",")).build();
            available = true;
            registerSelf();
            loadRetrievalConfig();
            startWatch();
            log.info("etcd 已连接: {}", endpoints);
        } catch (Exception e) {
            available = false;
            log.warn("etcd 连接失败，降级为本地默认配置: {}", e.getMessage());
        }
    }

    /** 服务注册：/rag/registry/{service}/{host:port}，租约 15s 自动续期。 */
    private void registerSelf() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String key = "/rag/registry/rag-api/" + host + ":" + "8080";
            long leaseId = client.getLeaseClient().grant(15).get().getID();
            client.getKVClient().put(bs(key), bs("active")).get();
            keepAliveClient = client.getLeaseClient().keepAlive(leaseId, noopObserver());
            log.info("etcd 服务注册完成: {}", key);
        } catch (Exception e) {
            log.warn("etcd 服务注册失败: {}", e.getMessage());
        }
    }

    private void loadRetrievalConfig() {
        try {
            KV kv = client.getKVClient();
            var resp = kv.get(bs("/rag/config/global/retrieval")).get();
            if (!resp.getKvs().isEmpty()) {
                applyRetrieval(resp.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.debug("etcd 读取检索配置失败（使用默认）: {}", e.getMessage());
        }
    }

    private void startWatch() {
        try {
            Watch watchClient = client.getWatchClient();
            watchClient.watch(bytes("/rag/config/"), WatchOption.builder().withPrefix(bytes("/rag/config/")).build(), response -> {
                for (WatchEvent event : response.getEvents()) {
                    String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
                    if ("/rag/config/global/retrieval".equals(key)) {
                        String value = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
                        applyRetrieval(value);
                        log.info("etcd 检索配置已热刷新");
                    }
                }
            });
        } catch (Exception e) {
            log.warn("etcd watch 启动失败: {}", e.getMessage());
        }
    }

    private void applyRetrieval(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            RetrievalConfig c = new RetrievalConfig();
            c.vectorTopN = node.path("vectorTopN").asInt(defaults.vectorTopN);
            c.keywordTopN = node.path("keywordTopN").asInt(defaults.keywordTopN);
            c.topK = node.path("topK").asInt(defaults.topK);
            c.rrfK = node.path("rrfK").asInt(defaults.rrfK);
            c.lowConfidenceThreshold = node.path("lowConfidenceThreshold").asDouble(defaults.lowConfidenceThreshold);
            retrievalConfig = c;
        } catch (Exception e) {
            log.warn("etcd 检索配置解析失败: {}", e.getMessage());
        }
    }

    /** 工具配置变更时写入 etcd，供其他实例 Watch 感知。 */
    public void putTenantToolConfig(String tenantId, String json) {
        if (!available) {
            return;
        }
        try {
            client.getKVClient().put(bs("/rag/config/tenant/" + tenantId + "/tools"), bs(json)).get();
        } catch (Exception e) {
            log.warn("etcd 写入工具配置失败: {}", e.getMessage());
        }
    }

    /** 服务注册：/rag/registry/{service}/{host:port}，租约 15s 自动续期。 */
    public void register(String service, String hostPort) {
        if (!available) {
            return;
        }
        try {
            String key = "/rag/registry/" + service + "/" + hostPort;
            long leaseId = client.getLeaseClient().grant(15).get().getID();
            client.getKVClient().put(bs(key), bs(hostPort)).get();
            keepAliveClient = client.getLeaseClient().keepAlive(leaseId, noopObserver());
            log.info("etcd 服务注册完成: {}", key);
        } catch (Exception e) {
            log.warn("etcd 服务注册失败: {}", e.getMessage());
        }
    }

    public List<String> listInstances(String service) {
        if (!available) {
            return List.of();
        }
        try {
            var resp = client.getKVClient().get(bs("/rag/registry/" + service + "/"),
                    GetOption.builder().isPrefix(true).build()).get();
            return resp.getKvs().stream()
                    .map(kv -> kv.getValue().toString(StandardCharsets.UTF_8))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (keepAliveClient != null) {
                keepAliveClient.close();
            }
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
        }
    }

    private ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    private StreamObserver<LeaseKeepAliveResponse> noopObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse response) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private ByteSequence bytes(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }
}
