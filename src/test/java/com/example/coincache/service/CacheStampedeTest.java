package com.example.coincache.service;

import com.example.coincache.domain.CoinQuote;
import com.example.coincache.support.CacheTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Cache Stampede (캐시 스탬피드)
 * - 상황: 핫 키가 만료되는 순간에 동시 요청이 몰리며 원천에 과부하 발생
 * - 실제로 터지는 지점: 원천 DB 쿼리 폭증, 외부 API 제한, 스레드/커넥션 풀 고갈
 * - 핵심 원인: 캐시 미스 순간의 동시성 제어가 없음
 *
 * - 대응 선택지:
 *   1) 분산 락(멀티 인스턴스 공통 제어)
 *   2) SingleFlight(인스턴스 내부 중복 합치기)
 *   3) 논리 만료 + SWR(만료 직후 stale 응답 제공)
 *
 * "원천 조회 횟수"로 스탬피드 완화 효과를 비교한 테스트
 */
@DisplayName("Cache Stampede 대응 테스트")
class CacheStampedeTest extends CacheTestSupport {

    /*
     * 대응 방식: Redis 분산 락(SET NX PX) + 토큰 기반 해제
     * 일반적으로 멀티 인스턴스 환경에서 가장 널리 쓰임(GPT 피셜)
     * 장점: 전체 인스턴스에서 원천 조회를 1회로 묶을 수 있음
     * 단점: 락 경합 시 지연 증가, Redis RTT에 의존
     *
     * 원천 조회가 1~3회 수준으로 제한되는지 확인
     */
    @Test
    @DisplayName("분산 락: 대량 동시 요청에서도 원천 조회를 최소화")
    void distributedLock_preventsStampede() throws InterruptedException {
        // 준비: 핫 키를 원천 데이터에 추가
        String symbol = "HOT_LOCK";
        repository.updateQuote(symbol, sampleQuote(symbol));

        // 실행: 동시에 대량 요청을 발생
        int requests = dataSize();
        runConcurrent(requests, threadCount(), () ->
                quoteCacheService.getQuoteWithDistributedLock(symbol)
        );

        // 검증: 원천 조회가 과도하게 증가하지 않았는지 확인
        assertThat(repository.getQueryCount()).isLessThanOrEqualTo(3);
    }

    /*
     * 대응 방식: 인스턴스 내부에서 동일 키 요청을 합쳐 1번만 원천 조회
     * 일반적으로 단일 인스턴스 성능 최적화에 자주 사용
     * 장점: 구현 단순, 분산 락 대비 RTT 오버헤드 적음
     * 단점: 인스턴스 간에는 중복 조회가 발생할 수 있음
     *
     * 이 테스트는 동일 인스턴스 내 요청이 1회로 합쳐지는지 확인
     */
    @Test
    @DisplayName("SingleFlight: 동일 인스턴스 내 중복 요청을 합친다")
    void singleFlight_deduplicatesInFlightRequests() throws InterruptedException {
        // 준비: 동일 키에 대한 원천 데이터를 만들기
        String symbol = "HOT_SF";
        repository.updateQuote(symbol, sampleQuote(symbol));

        // 실행: 여러 요청이 동시에 몰리도록 만들기
        int requests = dataSize();
        runConcurrent(requests, threadCount(), () ->
                quoteCacheService.getQuoteWithSingleFlight(symbol)
        );

        // 검증: 원천 조회가 1회 수준인지 확인
        assertThat(repository.getQueryCount()).isLessThanOrEqualTo(1);
    }

    /*
     * 대응 방식: 논리 만료 후에도 stale 응답을 주고 백그라운드 갱신
     * 일반적으로 트래픽이 매우 큰 시스템에서 안정성을 위해 사용
     * 장점: 응답 지연 없이 스탬피드를 완화
     * 단점: 일정 시간 동안 오래된 데이터가 노출될 수 있음
     *
     * 논리 만료 이후에도 원천 조회가 폭증하지 않는지 확인
     */
    @Test
    @DisplayName("Logical Expire: 만료 시점에도 stale 응답을 제공하고 백그라운드 갱신한다")
    void logicalExpire_servesStaleAndRefreshes() throws InterruptedException {
        // 준비: 논리 만료 캐시를 넣고, 만료되도록 대기
        String symbol = "HOT_LOGICAL";
        CoinQuote quote = sampleQuote(symbol);
        quoteCacheService.cacheQuoteWithLogicalExpire(symbol, quote);
        repository.resetQueryCount();

        Thread.sleep(2500);

        // 실행: 만료 이후에 대량 요청을 몰아주기
        int requests = dataSize();
        runConcurrent(requests, threadCount(), () ->
                quoteCacheService.getQuoteWithLogicalExpire(symbol)
        );

        // 검증: 원천 조회가 폭증하지 않았는지 확인
        assertThat(repository.getQueryCount()).isLessThanOrEqualTo(2);
    }

    private CoinQuote sampleQuote(String symbol) {
        return CoinQuote.builder()
                .symbol(symbol)
                .price(new BigDecimal("100.00"))
                .change24h(new BigDecimal("1.0"))
                .volume24h(new BigDecimal("1000000"))
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
