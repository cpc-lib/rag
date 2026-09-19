package com.rag.worker.mq;

import com.rag.worker.infrastructure.persistence.entity.TenantEntity;
import com.rag.worker.infrastructure.persistence.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 租户 MQ 并发额度（spec 3.1）：Redis 信号量，超出则消息 requeue 稍后再试。
 */
@Component
@RequiredArgsConstructor
public class TenantMqSemaphore {

    private static final String KEY_PREFIX = "rag:mq:conc:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('DECR', KEYS[1]); if v < 0 then redis.call('SET', KEYS[1], 0); return 0 end; return v",
            Long.class);

    private final StringRedisTemplate redis;
    private final TenantMapper tenantMapper;

    public boolean tryAcquire(String tenantId) {
        int limit = 4;
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant != null && tenant.getMaxMqConcurrency() != null) {
            limit = tenant.getMaxMqConcurrency();
        }
        String key = KEY_PREFIX + tenantId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(10));
        }
        if (count != null && count > limit) {
            release(tenantId);
            return false;
        }
        return true;
    }

    public void release(String tenantId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + tenantId));
        } catch (Exception ignored) {
        }
    }
}
