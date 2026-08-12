package com.agentforge.prreview.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed replay store shared by every replica and preserved across restarts.
 */
@Component
@RequiredArgsConstructor
public class RedisWebhookDeliveryStore implements WebhookDeliveryStore {

    private static final String KEY_PREFIX = "pr-review:webhook-delivery:";

    private final StringRedisTemplate redisTemplate;

    @Value("${review.webhook.delivery-retention:24h}")
    private Duration deliveryRetention;

    @Override
    public boolean recordIfNew(String replayKey) {
        Boolean inserted = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + replayKey, "processed", deliveryRetention);
        return Boolean.TRUE.equals(inserted);
    }

    @Override
    public void release(String replayKey) {
        redisTemplate.delete(KEY_PREFIX + replayKey);
    }
}
