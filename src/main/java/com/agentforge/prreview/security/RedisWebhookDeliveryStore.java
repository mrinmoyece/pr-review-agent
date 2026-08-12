package com.agentforge.prreview.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed replay store shared by every replica and preserved across restarts.
 */
@Component
@RequiredArgsConstructor
public class RedisWebhookDeliveryStore implements WebhookDeliveryStore {

    private static final String KEY_PREFIX = "pr-review:webhook-delivery:";
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${review.webhook.delivery-retention:24h}")
    private Duration deliveryRetention;

    @Override
    public boolean recordIfNew(String replayKey, String reservationToken) {
        Boolean inserted = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + replayKey, reservationToken, deliveryRetention);
        return Boolean.TRUE.equals(inserted);
    }

    @Override
    public void release(String replayKey, String reservationToken) {
        redisTemplate.execute(RELEASE_IF_OWNER,
                List.of(KEY_PREFIX + replayKey), reservationToken);
    }
}
