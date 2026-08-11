package com.agentforge.prreview.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisWebhookDeliveryStoreTest {

    private ValueOperations<String, String> valueOperations;
    private RedisWebhookDeliveryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisWebhookDeliveryStore(redisTemplate);
        ReflectionTestUtils.setField(store, "deliveryRetention", Duration.ofHours(24));
    }

    @Test
    void newDeliveryIsAccepted() {
        when(valueOperations.setIfAbsent(
                "pr-review:webhook-delivery:delivery-1", "processed", Duration.ofHours(24)))
                .thenReturn(true);

        assertThat(store.recordIfNew("delivery-1")).isTrue();
    }

    @Test
    void existingDeliveryIsRejected() {
        when(valueOperations.setIfAbsent(
                "pr-review:webhook-delivery:delivery-1", "processed", Duration.ofHours(24)))
                .thenReturn(false);

        assertThat(store.recordIfNew("delivery-1")).isFalse();
    }
}
