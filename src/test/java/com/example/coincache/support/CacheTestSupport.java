package com.example.coincache.support;

import com.example.coincache.repository.InMemoryCoinQuoteRepository;
import com.example.coincache.service.QuoteCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ActiveProfiles("test")
public abstract class CacheTestSupport {

    @Autowired
    protected QuoteCacheService quoteCacheService;

    @Autowired
    protected InMemoryCoinQuoteRepository repository;

    @Autowired
    protected RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        repository.resetQueryCount();
        repository.resetData();
    }

    protected int dataSize() {
        return Integer.parseInt(System.getProperty("test.data.size", "10000"));
    }

    protected int threadCount() {
        return Math.min(200, dataSize());
    }

    protected List<String> seedSymbols(int count, String prefix) {
        return repository.seedQuotes(count, prefix);
    }

    protected List<String> generateSymbols(String prefix, int count) {
        List<String> symbols = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            symbols.add(String.format("%s%05d", prefix, i));
        }
        return symbols;
    }

    protected void runConcurrent(int tasks, int threads, Runnable action) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    action.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        if (!completed) {
            throw new IllegalStateException("Concurrent run timed out");
        }
    }
}
