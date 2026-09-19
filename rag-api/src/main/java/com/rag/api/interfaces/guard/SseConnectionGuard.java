package com.rag.api.interfaces.guard;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SSE 并发限流：Redis 计数按租户限制长连接数（spec 5.3）。
 */
@Component
public class SseConnectionGuard {

    private static final String KEY_PREFIX = "rag:sse:conn:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('DECR', KEYS[1]); if v < 0 then redis.call('SET', KEYS[1], 0); return 0 end; return v",
            Long.class);

    private final StringRedisTemplate redis;

    public SseConnectionGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void acquire(String tenantId, int maxConnections) {
        String key = KEY_PREFIX + tenantId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, java.time.Duration.ofMinutes(10));
        }
        if (count != null && count > maxConnections) {
            release(tenantId);
            throw new BizException(ErrorCode.SSE_LIMIT,
                    "当前租户 SSE 并发连接已达上限（%d），请稍后重试".formatted(maxConnections));
        }
    }

    public void release(String tenantId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + tenantId));
        } catch (Exception ignored) {
        }
    }

    public long current(String tenantId) {
        String v = redis.opsForValue().get(KEY_PREFIX + tenantId);
        return v == null ? 0 : Long.parseLong(v);
    }
}
