package com.agentforge.prreview.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatNoException;

class ReviewExecutionConfigTest {

    private ThreadPoolTaskExecutor outerExecutor;
    private ThreadPoolTaskExecutor fanOutExecutor;

    @AfterEach
    void tearDown() {
        if (outerExecutor != null) {
            outerExecutor.shutdown();
        }
        if (fanOutExecutor != null) {
            fanOutExecutor.shutdown();
        }
    }

    @Test
    void concurrentReviewsDoNotBlockTheirOwnFanOutTasks() {
        ReviewExecutionConfig config = new ReviewExecutionConfig();
        outerExecutor = (ThreadPoolTaskExecutor) config.reviewExecutor();
        fanOutExecutor = (ThreadPoolTaskExecutor) config.reviewFanOutExecutor();
        int concurrentReviews = outerExecutor.getCorePoolSize();
        CyclicBarrier barrier = new CyclicBarrier(concurrentReviews);

        List<CompletableFuture<Void>> reviews = java.util.stream.IntStream.range(0, concurrentReviews)
                .mapToObj(ignored -> CompletableFuture.runAsync(
                        () -> {
                            await(barrier);
                            runFanOut(fanOutExecutor);
                        }, outerExecutor))
                .toList();

        assertThatNoException().isThrownBy(() ->
                CompletableFuture.allOf(reviews.toArray(CompletableFuture[]::new))
                        .orTimeout(Duration.ofSeconds(5).toMillis(),
                                java.util.concurrent.TimeUnit.MILLISECONDS)
                        .join());
    }

    private void runFanOut(Executor executor) {
        CompletableFuture<?>[] tasks = java.util.stream.IntStream.range(0, 6)
                .mapToObj(ignored -> CompletableFuture.runAsync(() -> { }, executor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tasks).join();
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent review barrier failed", e);
        }
    }
}
