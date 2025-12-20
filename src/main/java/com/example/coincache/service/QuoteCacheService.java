package com.example.coincache.service;

import com.example.coincache.cache.CacheValue;
import com.example.coincache.config.CacheProperties;
import com.example.coincache.domain.CoinQuote;
import com.example.coincache.repository.CoinQuoteRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 코인 시세 캐시 서비스
 *
 * 구현된 캐싱 전략:
 * 1. Cache-Aside (Lazy Loading)
 * 2. 캐시 스탬피드 방지 (분산 락, SingleFlight, Logical Expire)
 * 3. 캐시 애벌랜치 방지 (TTL Jitter)
 * 4. 캐시 관통 방지 (Null Cache + 화이트리스트)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteCacheService {

    private static final String CACHE_KEY_PREFIX = "quotes:";
    private static final String LOCK_KEY_PREFIX = "lock:quotes:";
    private static final String LOGICAL_CACHE_KEY_PREFIX = "quotes:logical:";
    private static final String LOGICAL_LOCK_KEY_PREFIX = "lock:quotes:logical:";
    private static final String NULL_MARKER = "__NULL__";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final CoinQuoteRepository repository;
    private final CacheProperties cacheProperties;

    private final ConcurrentHashMap<String, CompletableFuture<Optional<CoinQuote>>> inFlightRequests =
            new ConcurrentHashMap<>();

    private ExecutorService refreshExecutor;

    @PostConstruct
    public void init() {
        refreshExecutor = Executors.newFixedThreadPool(cacheProperties.getRefreshThreads());
    }

    @PreDestroy
    public void shutdown() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdown();
        }
    }

    /**
     * 시세 조회 (Cache-Aside + 분산 락)
     */
    public Optional<CoinQuote> getQuote(String symbol) {
        return getQuoteWithSymbolCheck(symbol, repository::existsSymbol, this::getQuoteInternal);
    }

    /**
     * 화이트리스트/필터를 주입하는 조회 (테스트/전략 실험용)
     */
    public Optional<CoinQuote> getQuoteWithSymbolFilter(String symbol, Predicate<String> symbolFilter) {
        return getQuoteWithSymbolCheck(symbol, symbolFilter, this::getQuoteInternal);
    }

    /**
     * 분산 락 기반 조회 (Cache Stampede 방지)
     */
    public Optional<CoinQuote> getQuoteWithDistributedLock(String symbol) {
        return getQuoteWithSymbolCheck(symbol, repository::existsSymbol, this::getQuoteInternal);
    }

    /**
     * SingleFlight 기반 조회 (동일 인스턴스 내 요청 합치기)
     */
    public Optional<CoinQuote> getQuoteWithSingleFlight(String symbol) {
        return getQuoteWithSymbolCheck(symbol, repository::existsSymbol, this::getQuoteWithSingleFlightInternal);
    }

    /**
     * Logical Expire + Stale-While-Revalidate 조회
     */
    public Optional<CoinQuote> getQuoteWithLogicalExpire(String symbol) {
        return getQuoteWithSymbolCheck(symbol, repository::existsSymbol, this::getQuoteWithLogicalExpireInternal);
    }

    private Optional<CoinQuote> getQuoteWithSymbolCheck(
            String symbol,
            Predicate<String> symbolFilter,
            Function<String, Optional<CoinQuote>> loader
    ) {
        if (!symbolFilter.test(symbol)) {
            log.debug("[심볼 차단] 존재하지 않는 심볼: {}", symbol);
            return Optional.empty();
        }
        return loader.apply(symbol);
    }

    private Optional<CoinQuote> getQuoteInternal(String symbol) {
        String cacheKey = getCacheKey(symbol);

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (NULL_MARKER.equals(cached)) {
                log.debug("[Null 캐시 히트] symbol={}", symbol);
                return Optional.empty();
            }
            log.debug("[캐시 히트] symbol={}", symbol);
            return Optional.of((CoinQuote) cached);
        }

        log.debug("[캐시 미스] symbol={}", symbol);
        return loadWithLock(symbol, cacheKey);
    }

    private Optional<CoinQuote> getQuoteWithSingleFlightInternal(String symbol) {
        String cacheKey = getCacheKey(symbol);

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (NULL_MARKER.equals(cached)) {
                return Optional.empty();
            }
            return Optional.of((CoinQuote) cached);
        }

        CompletableFuture<Optional<CoinQuote>> future = new CompletableFuture<>();
        CompletableFuture<Optional<CoinQuote>> existing = inFlightRequests.putIfAbsent(cacheKey, future);
        if (existing == null) {
            try {
                Optional<CoinQuote> quote = loadFromRepositoryAndCache(symbol, cacheKey);
                future.complete(quote);
                return quote;
            } catch (Exception e) {
                future.completeExceptionally(e);
                throw e;
            } finally {
                inFlightRequests.remove(cacheKey);
            }
        }

        try {
            return existing.get(cacheProperties.getSingleFlightWaitMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[SingleFlight 대기 실패] 직접 원천 조회 - symbol={}", symbol);
            return loadFromRepositoryAndCache(symbol, cacheKey);
        }
    }

    private Optional<CoinQuote> getQuoteWithLogicalExpireInternal(String symbol) {
        String cacheKey = getLogicalCacheKey(symbol);

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached == null) {
            return loadFromRepositoryAndLogicalCache(symbol, cacheKey);
        }

        CacheValue<CoinQuote> cacheValue = (CacheValue<CoinQuote>) cached;
        if (!cacheValue.isExpired()) {
            return Optional.ofNullable(cacheValue.getValue());
        }

        triggerAsyncRefresh(symbol, cacheKey);
        return Optional.ofNullable(cacheValue.getValue());
    }

    /**
     * 분산 락을 활용한 원천 조회 (Cache Stampede 방지)
     */
    private Optional<CoinQuote> loadWithLock(String symbol, String cacheKey) {
        String lockKey = getLockKey(symbol);
        String token = UUID.randomUUID().toString();

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, Duration.ofMillis(cacheProperties.getLockTimeoutMs()));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                log.debug("[락 획득] 원천 조회 시작 - symbol={}", symbol);
                return loadFromRepositoryAndCache(symbol, cacheKey);
            } finally {
                releaseLock(lockKey, token);
                log.debug("[락 해제] symbol={}", symbol);
            }
        }

        log.debug("[락 대기] 다른 요청이 갱신 중 - symbol={}", symbol);
        return waitAndRetry(symbol, cacheKey);
    }

    private Optional<CoinQuote> waitAndRetry(String symbol, String cacheKey) {
        try {
            Thread.sleep(cacheProperties.getLockTimeoutMs() / 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (NULL_MARKER.equals(cached)) {
                return Optional.empty();
            }
            log.debug("[재시도 캐시 히트] symbol={}", symbol);
            return Optional.of((CoinQuote) cached);
        }

        log.warn("[재시도 실패] 직접 원천 조회 - symbol={}", symbol);
        return loadFromRepositoryAndCache(symbol, cacheKey);
    }

    private Optional<CoinQuote> loadFromRepositoryAndCache(String symbol, String cacheKey) {
        Optional<CoinQuote> quote = repository.findBySymbol(symbol);
        if (quote.isPresent()) {
            saveToCache(cacheKey, quote.get());
        } else {
            saveNullCache(cacheKey);
        }
        return quote;
    }

    private Optional<CoinQuote> loadFromRepositoryAndLogicalCache(String symbol, String cacheKey) {
        Optional<CoinQuote> quote = repository.findBySymbol(symbol);
        if (quote.isPresent()) {
            saveToLogicalCache(cacheKey, quote.get());
        } else {
            saveLogicalNullCache(cacheKey);
        }
        return quote;
    }

    private void triggerAsyncRefresh(String symbol, String cacheKey) {
        String lockKey = getLogicalLockKey(symbol);
        String token = UUID.randomUUID().toString();

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, Duration.ofMillis(cacheProperties.getLockTimeoutMs()));

        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        refreshExecutor.submit(() -> {
            try {
                Optional<CoinQuote> quote = repository.findBySymbol(symbol);
                if (quote.isPresent()) {
                    saveToLogicalCache(cacheKey, quote.get());
                } else {
                    saveLogicalNullCache(cacheKey);
                }
            } finally {
                releaseLock(lockKey, token);
            }
        });
    }

    private void saveToCache(String cacheKey, CoinQuote quote) {
        Duration ttl = calculateTtlWithJitter();
        saveToCache(cacheKey, quote, ttl);
        log.debug("[캐시 저장] key={}, ttl={}s", cacheKey, ttl.getSeconds());
    }

    private void saveToCache(String cacheKey, CoinQuote quote, Duration ttl) {
        redisTemplate.opsForValue().set(cacheKey, quote, ttl);
    }

    private void saveNullCache(String cacheKey) {
        Duration ttl = Duration.ofSeconds(cacheProperties.getNullCacheTtlSeconds());
        redisTemplate.opsForValue().set(cacheKey, NULL_MARKER, ttl);
        log.debug("[Null 캐시 저장] key={}, ttl={}s", cacheKey, ttl.getSeconds());
    }

    private void saveToLogicalCache(String cacheKey, CoinQuote quote) {
        long expireAt = System.currentTimeMillis()
                + Duration.ofSeconds(cacheProperties.getLogicalExpireSeconds()).toMillis();
        CacheValue<CoinQuote> cacheValue = new CacheValue<>(quote, expireAt);
        Duration ttl = Duration.ofSeconds(
                cacheProperties.getLogicalExpireSeconds() + cacheProperties.getStaleTtlBufferSeconds());
        redisTemplate.opsForValue().set(cacheKey, cacheValue, ttl);
    }

    private void saveLogicalNullCache(String cacheKey) {
        long expireAt = System.currentTimeMillis()
                + Duration.ofSeconds(cacheProperties.getLogicalExpireSeconds()).toMillis();
        CacheValue<CoinQuote> cacheValue = new CacheValue<>(null, expireAt);
        Duration ttl = Duration.ofSeconds(
                cacheProperties.getLogicalExpireSeconds() + cacheProperties.getStaleTtlBufferSeconds());
        redisTemplate.opsForValue().set(cacheKey, cacheValue, ttl);
    }

    private Duration calculateTtlWithJitter() {
        int baseTtl = cacheProperties.getBaseTtlSeconds();
        int jitter = java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(0, cacheProperties.getTtlJitterSeconds() + 1);
        return Duration.ofSeconds(baseTtl + jitter);
    }

    private Duration calculateTtlWithHashJitter(String cacheKey) {
        int baseTtl = cacheProperties.getBaseTtlSeconds();
        int jitterRange = cacheProperties.getTtlJitterSeconds();
        int hash = cacheKey.hashCode();
        int safeHash = hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
        int offset = safeHash % (jitterRange + 1);
        return Duration.ofSeconds(baseTtl + offset);
    }

    public void cacheQuoteWithFixedTtl(String symbol, CoinQuote quote, Duration ttl) {
        saveToCache(getCacheKey(symbol), quote, ttl);
    }

    public void cacheQuoteWithRandomJitter(String symbol, CoinQuote quote) {
        saveToCache(getCacheKey(symbol), quote);
    }

    public void cacheQuoteWithHashJitter(String symbol, CoinQuote quote) {
        String cacheKey = getCacheKey(symbol);
        Duration ttl = calculateTtlWithHashJitter(cacheKey);
        saveToCache(cacheKey, quote, ttl);
    }

    public void cacheQuoteWithoutTtl(String symbol, CoinQuote quote) {
        redisTemplate.opsForValue().set(getCacheKey(symbol), quote);
    }

    public void cacheQuoteWithLogicalExpire(String symbol, CoinQuote quote) {
        saveToLogicalCache(getLogicalCacheKey(symbol), quote);
    }

    /**
     * 캐시 강제 갱신 (Push 기반 갱신용)
     */
    public void refreshCache(String symbol, CoinQuote quote) {
        String cacheKey = getCacheKey(symbol);
        saveToCache(cacheKey, quote);
        log.info("[캐시 강제 갱신] symbol={}", symbol);
    }

    /**
     * 캐시 삭제
     */
    public void evictCache(String symbol) {
        String cacheKey = getCacheKey(symbol);
        redisTemplate.delete(cacheKey);
        log.info("[캐시 삭제] symbol={}", symbol);
    }

    private void releaseLock(String lockKey, String token) {
        stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
    }

    private String getCacheKey(String symbol) {
        return CACHE_KEY_PREFIX + symbol;
    }

    private String getLockKey(String symbol) {
        return LOCK_KEY_PREFIX + symbol;
    }

    private String getLogicalCacheKey(String symbol) {
        return LOGICAL_CACHE_KEY_PREFIX + symbol;
    }

    private String getLogicalLockKey(String symbol) {
        return LOGICAL_LOCK_KEY_PREFIX + symbol;
    }
}
