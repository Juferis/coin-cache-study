package com.example.coincache.service;

import com.example.coincache.config.CacheProperties;
import com.example.coincache.domain.CoinQuote;
import com.example.coincache.repository.CoinQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 코인 시세 캐시 서비스
 *
 * 구현된 캐싱 전략:
 * 1. Cache-Aside (Lazy Loading)
 * 2. 캐시 스탬피드 방지 (분산 락)
 * 3. 캐시 애벌랜치 방지 (TTL Jitter)
 * 4. 캐시 관통 방지 (Null Cache + 화이트리스트)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteCacheService {

    private static final String CACHE_KEY_PREFIX = "quotes:";
    private static final String LOCK_KEY_PREFIX = "lock:quotes:";
    private static final String NULL_MARKER = "__NULL__";

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final CoinQuoteRepository repository;
    private final CacheProperties cacheProperties;

    /**
     * 시세 조회 (Cache-Aside 패턴)
     */
    public Optional<CoinQuote> getQuote(String symbol) {
        // 1. 화이트리스트 검증 (Cache Penetration 방지 - 1차)
        if (!repository.existsSymbol(symbol)) {
            log.debug("[화이트리스트 차단] 존재하지 않는 심볼: {}", symbol);
            return Optional.empty();
        }

        String cacheKey = getCacheKey(symbol);

        // 2. 캐시 조회
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        // 2-1. Cache Hit
        if (cached != null) {
            // Null Cache 체크 (Cache Penetration 방지 - 2차)
            if (NULL_MARKER.equals(cached)) {
                log.debug("[Null 캐시 히트] symbol={}", symbol);
                return Optional.empty();
            }
            log.debug("[캐시 히트] symbol={}", symbol);
            return Optional.of((CoinQuote) cached);
        }

        // 2-2. Cache Miss - 분산 락으로 원천 조회 단일화
        log.debug("[캐시 미스] symbol={}", symbol);
        return loadWithLock(symbol, cacheKey);
    }

    /**
     * 분산 락을 활용한 원천 조회 (Cache Stampede 방지)
     */
    private Optional<CoinQuote> loadWithLock(String symbol, String cacheKey) {
        String lockKey = getLockKey(symbol);

        // 락 획득 시도 (SET NX PX)
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofMillis(cacheProperties.getLockTimeoutMs()));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                log.debug("[락 획득] 원천 조회 시작 - symbol={}", symbol);

                // 원천 조회
                Optional<CoinQuote> quote = repository.findBySymbol(symbol);

                // 캐시 저장
                if (quote.isPresent()) {
                    saveToCache(cacheKey, quote.get());
                } else {
                    // Null Cache 저장 (Cache Penetration 방지)
                    saveNullCache(cacheKey);
                }

                return quote;

            } finally {
                // 락 해제
                stringRedisTemplate.delete(lockKey);
                log.debug("[락 해제] symbol={}", symbol);
            }
        } else {
            // 락 획득 실패 - 짧은 대기 후 캐시 재조회 (stale data 가능)
            log.debug("[락 대기] 다른 요청이 갱신 중 - symbol={}", symbol);
            return waitAndRetry(symbol, cacheKey);
        }
    }

    /**
     * 락 획득 실패 시 대기 후 재시도
     */
    private Optional<CoinQuote> waitAndRetry(String symbol, String cacheKey) {
        try {
            // 짧은 대기 (락 타임아웃의 절반)
            Thread.sleep(cacheProperties.getLockTimeoutMs() / 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 캐시 재조회
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !NULL_MARKER.equals(cached)) {
            log.debug("[재시도 캐시 히트] symbol={}", symbol);
            return Optional.of((CoinQuote) cached);
        }

        // 여전히 없으면 직접 조회 (극히 드문 케이스)
        log.warn("[재시도 실패] 직접 원천 조회 - symbol={}", symbol);
        return repository.findBySymbol(symbol);
    }

    /**
     * 캐시 저장 (TTL Jitter 적용 - Cache Avalanche 방지)
     */
    private void saveToCache(String cacheKey, CoinQuote quote) {
        Duration ttl = calculateTtlWithJitter();
        redisTemplate.opsForValue().set(cacheKey, quote, ttl);
        log.debug("[캐시 저장] key={}, ttl={}s", cacheKey, ttl.getSeconds());
    }

    /**
     * Null 캐시 저장 (짧은 TTL)
     */
    private void saveNullCache(String cacheKey) {
        Duration ttl = Duration.ofSeconds(cacheProperties.getNullCacheTtlSeconds());
        redisTemplate.opsForValue().set(cacheKey, NULL_MARKER, ttl);
        log.debug("[Null 캐시 저장] key={}, ttl={}s", cacheKey, ttl.getSeconds());
    }

    /**
     * TTL + Jitter 계산 (Cache Avalanche 방지)
     */
    private Duration calculateTtlWithJitter() {
        int baseTtl = cacheProperties.getBaseTtlSeconds();
        int jitter = ThreadLocalRandom.current().nextInt(0, cacheProperties.getTtlJitterSeconds() + 1);
        return Duration.ofSeconds(baseTtl + jitter);
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

    private String getCacheKey(String symbol) {
        return CACHE_KEY_PREFIX + symbol;
    }

    private String getLockKey(String symbol) {
        return LOCK_KEY_PREFIX + symbol;
    }
}
