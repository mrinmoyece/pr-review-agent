package com.agentforge.prreview.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisWebhookDeliveryStoreTest {

    private ValueOperations<String, String> valueOperations;
    private StringRedisTemplate redisTemplate;
    private RedisWebhookDeliveryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisWebhookDeliveryStore(redisTemplate);
        ReflectionTestUtils.setField(store, "deliveryRetention", Duration.ofHours(24));
    }

    @Test
    void newDeliveryIsAccepted() {
        when(valueOperations.setIfAbsent(
                "pr-review:webhook-delivery:delivery-1", "owner-1", Duration.ofHours(24)))
                .thenReturn(true);

        assertThat(store.recordIfNew("delivery-1", "owner-1")).isTrue();
    }

    @Test
    void existingDeliveryIsRejected() {
        when(valueOperations.setIfAbsent(
                "pr-review:webhook-delivery:delivery-1", "owner-2", Duration.ofHours(24)))
                .thenReturn(false);

        assertThat(store.recordIfNew("delivery-1", "owner-2")).isFalse();
    }

    @Test
    void failedDeliveryCanBeReleasedForRedelivery() {
        store.release("delivery-1", "owner-1");

        verify(redisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("pr-review:webhook-delivery:delivery-1")), eq("owner-1"));
    }
}
