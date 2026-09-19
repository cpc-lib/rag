package com.rag.worker.infrastructure.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Worker 服务注册：/rag/registry/rag-worker/{host:port}，租约 15s keepalive；etcd 不可用时降级。
 */
@Slf4j
@Service
public class WorkerRegistry {

    private final String endpoints;
    private final boolean enabled;
    private volatile Client client;
    private volatile io.etcd.jetcd.support.CloseableClient keepAlive;

    public WorkerRegistry(@Value("${rag.etcd.endpoints:http://192.168.1.200:2379}") String endpoints,
                          @Value("${rag.etcd.enabled:true}") boolean enabled) {
        this.endpoints = endpoints;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        try {
            client = Client.builder().endpoints(endpoints.split(",")).build();
            String host = InetAddress.getLocalHost().getHostName();
            String key = "/rag/registry/rag-worker/" + host;
            long leaseId = client.getLeaseClient().grant(15).get().getID();
            client.getKVClient().put(bs(key), bs(host)).get();
            keepAlive = client.getLeaseClient().keepAlive(leaseId, noopObserver());
            log.info("etcd 服务注册完成: {}", key);
        } catch (Exception e) {
            log.warn("etcd 服务注册失败（降级）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (keepAlive != null) {
                keepAlive.close();
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
}
