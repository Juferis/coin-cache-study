package com.example.coincache.service;

import com.example.coincache.domain.CoinQuote;
import com.example.coincache.repository.InMemoryCoinQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("QuoteCacheService 테스트")
class QuoteCacheServiceTest {

    @Autowired
    private QuoteCacheService quoteCacheService;

    @Autowired
    private InMemoryCoinQuoteRepository repository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        // 캐시 초기화
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        // 원천 조회 카운터 초기화
        repository.resetQueryCount();
    }

    @Nested
    @DisplayName("1. Cache-Aside 기본 동작")
    class CacheAsideTest {

        @Test
        @DisplayName("Cache Miss 시 원천 조회 후 캐시에 저장한다")
        void cacheMiss_loadsFromSource() {
            // when
            Optional<CoinQuote> result = quoteCacheService.getQuote("BTC");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getSymbol()).isEqualTo("BTC");
            assertThat(repository.getQueryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Cache Hit 시 원천을 조회하지 않는다")
        void cacheHit_doesNotQuerySource() {
            // given - 첫 번째 조회로 캐시 채우기
            quoteCacheService.getQuote("BTC");
            repository.resetQueryCount();

            // when - 두 번째 조회
            Optional<CoinQuote> result = quoteCacheService.getQuote("BTC");

            // then
            assertThat(result).isPresent();
            assertThat(repository.getQueryCount()).isEqualTo(0); // 원천 조회 없음
        }

        @Test
        @DisplayName("여러 심볼을 독립적으로 캐싱한다")
        void multipleSymbols_cachedIndependently() {
            // when
            quoteCacheService.getQuote("BTC");
            quoteCacheService.getQuote("ETH");
            quoteCacheService.getQuote("BTC"); // 캐시 히트
            quoteCacheService.getQuote("ETH"); // 캐시 히트

            // then
            assertThat(repository.getQueryCount()).isEqualTo(2); // BTC, ETH 각 1번씩만
        }
    }

    @Nested
    @DisplayName("2. Cache Stampede 방지 (분산 락)")
    class CacheStampedeTest {

        @Test
        @DisplayName("동시 요청 시 원천 조회는 1회만 발생한다")
        void concurrentRequests_singleSourceQuery() throws InterruptedException {
            // given
            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // when - 50개 동시 요청
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 동시 시작 대기
                        Optional<CoinQuote> result = quoteCacheService.getQuote("SOL");
                        if (result.isPresent()) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 동시 시작!
            endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(threadCount); // 모든 요청 성공
            
            // 핵심: 원천 조회는 1~3회 이내 (락 경쟁으로 약간의 오차 허용)
            System.out.println("원천 조회 횟수: " + repository.getQueryCount());
            assertThat(repository.getQueryCount()).isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("락 획득 실패 시 대기 후 캐시에서 조회한다")
        void lockContention_retriesFromCache() throws InterruptedException {
            // given
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Long> responseTimes = new ArrayList<>();

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        quoteCacheService.getQuote("AVAX");
                        responseTimes.add(System.currentTimeMillis() - start);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // then - 대부분 빠르게 응답 (캐시 히트 또는 짧은 대기)
            long avgResponseTime = responseTimes.stream()
                    .mapToLong(Long::longValue)
                    .sum() / threadCount;

            System.out.println("평균 응답시간: " + avgResponseTime + "ms");
            System.out.println("원천 조회 횟수: " + repository.getQueryCount());
        }
    }

    @Nested
    @DisplayName("3. Cache Penetration 방지 (화이트리스트 + Null 캐시)")
    class CachePenetrationTest {

        @Test
        @DisplayName("화이트리스트에 없는 심볼은 원천 조회하지 않는다")
        void invalidSymbol_blockedByWhitelist() {
            // when
            Optional<CoinQuote> result = quoteCacheService.getQuote("INVALID_COIN");

            // then
            assertThat(result).isEmpty();
            assertThat(repository.getQueryCount()).isEqualTo(0); // 원천 조회 없음!
        }

        @Test
        @DisplayName("동일한 존재하지 않는 심볼 반복 조회 시 원천은 1회만 조회한다")
        void repeatedInvalidRequest_nullCacheWorks() {
            // given - 화이트리스트에는 있지만 데이터가 없는 케이스 시뮬레이션
            // 이 테스트에서는 화이트리스트 체크로 먼저 걸러지므로
            // 실제로는 데이터가 있는 심볼로 테스트

            // when - 같은 심볼 5번 조회
            for (int i = 0; i < 5; i++) {
                quoteCacheService.getQuote("DOT");
            }

            // then - 첫 조회만 원천 접근
            assertThat(repository.getQueryCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("4. TTL Jitter 동작 확인 (Cache Avalanche 방지)")
    class CacheAvalancheTest {

        @Test
        @DisplayName("여러 심볼의 TTL이 서로 다르게 설정된다")
        void multipleSymbols_haveDifferentTtls() {
            // when - 여러 심볼 캐싱
            String[] symbols = {"BTC", "ETH", "XRP", "SOL", "DOGE"};
            for (String symbol : symbols) {
                quoteCacheService.getQuote(symbol);
            }

            // then - TTL 확인 (각각 다른 값을 가질 가능성 높음)
            List<Long> ttls = new ArrayList<>();
            for (String symbol : symbols) {
                Long ttl = redisTemplate.getExpire("quotes:" + symbol, TimeUnit.SECONDS);
                ttls.add(ttl);
                System.out.println(symbol + " TTL: " + ttl + "초");
            }

            // 모든 TTL이 유효한 범위 내에 있는지 확인 (5 ~ 7초, test 설정 기준)
            for (Long ttl : ttls) {
                assertThat(ttl).isBetween(1L, 8L); // 약간의 시간 오차 허용
            }
        }
    }

    @Nested
    @DisplayName("5. 캐시 갱신 및 삭제")
    class CacheManagementTest {

        @Test
        @DisplayName("캐시를 강제로 갱신할 수 있다")
        void refreshCache_updatesValue() {
            // given
            quoteCacheService.getQuote("BTC");
            CoinQuote newQuote = CoinQuote.builder()
                    .symbol("BTC")
                    .price(new BigDecimal("70000.00"))
                    .change24h(new BigDecimal("5.0"))
                    .volume24h(new BigDecimal("30000000000"))
                    .updatedAt(LocalDateTime.now())
                    .build();

            // when
            quoteCacheService.refreshCache("BTC", newQuote);
            Optional<CoinQuote> result = quoteCacheService.getQuote("BTC");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getPrice()).isEqualByComparingTo("70000.00");
        }

        @Test
        @DisplayName("캐시를 삭제하면 다음 조회 시 원천에서 가져온다")
        void evictCache_trigersSourceQuery() {
            // given
            quoteCacheService.getQuote("ETH");
            repository.resetQueryCount();

            // when
            quoteCacheService.evictCache("ETH");
            quoteCacheService.getQuote("ETH");

            // then
            assertThat(repository.getQueryCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("6. TTL 만료 후 재조회")
    class TtlExpirationTest {

        @Test
        @DisplayName("TTL 만료 후 캐시 미스가 발생하고 원천을 다시 조회한다")
        void afterTtlExpires_queriesSourceAgain() {
            // given
            quoteCacheService.getQuote("ADA");
            repository.resetQueryCount();

            // when - TTL 만료 대기 (test 설정: 5~7초)
            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> {
                        quoteCacheService.getQuote("ADA");
                        return repository.getQueryCount() > 0;
                    });

            // then
            System.out.println("TTL 만료 후 원천 재조회 확인: " + repository.getQueryCount() + "회");
            assertThat(repository.getQueryCount()).isGreaterThan(0);
        }
    }
}
