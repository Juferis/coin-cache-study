package com.example.coincache.service;

import com.example.coincache.domain.CoinQuote;
import com.example.coincache.support.CacheTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Cache Avalanche (캐시 애벌랜치)
 * - 상황: 수천~수만 개 키가 같은 타이밍에 만료되면서 캐시 미스가 폭주
 * - 실제로 터지는 지점: 원천 DB 커넥션 풀, 외부 API rate limit, 서비스 스레드 풀
 * - 핵심 원인: TTL이 동기화되어 있거나 갱신 주기가 한 덩어리로 몰림
 * - 해결 방향: "만료 시점 분산" 또는 "만료 자체 제거"
 *
 * 전략별로 만료 시점이 어떻게 퍼지는지 확인
 * 추가로 적용 가능한 대안: 논리 만료(SWR), 배치 프리워밍, 갱신 이벤트 기반 전략
 */
@DisplayName("Cache Avalanche 대응 테스트")
class CacheAvalancheTest extends CacheTestSupport {

    /*
     * 문제 상황 재현: 동일 TTL을 대량 키에 부여하면 만료가 "같이" 몰린다
     * 일반적으로는 운영에서 피해야 하는 방식(데모/실험 목적)
     * 장점: 구현이 가장 단순
     * 단점: 만료 순간에 원천 트래픽이 폭발할 확률이 가장 큼
     *
     * 이 테스트는 TTL이 거의 같은 범위로 몰리는지 확인
     */
    @Test
    @DisplayName("고정 TTL은 키들이 거의 동시에 만료된다")
    void fixedTtl_alignsExpiration() {
        // 준비: 대량 키를 만들어 동일 TTL로 저장
        List<String> symbols = generateSymbols("AVF", dataSize());
        for (String symbol : symbols) {
            // 준비: 모든 키에 같은 TTL을 부여 -> 만료 시점 동기화
            quoteCacheService.cacheQuoteWithFixedTtl(
                    symbol,
                    CoinQuote.empty(symbol),
                    Duration.ofSeconds(30)
            );
        }

        Set<Long> uniqueTtls = new HashSet<>();
        for (String symbol : symbols) {
            // 실행: Redis에 저장된 TTL(초)을 모아서 분포를 확인
            uniqueTtls.add(redisTemplate.getExpire("quotes:" + symbol, TimeUnit.SECONDS));
        }

        // 검증: TTL 범위가 거의 같으면 "동시 만료"
        long minTtl = uniqueTtls.stream().mapToLong(Long::longValue).min().orElse(0L);
        long maxTtl = uniqueTtls.stream().mapToLong(Long::longValue).max().orElse(0L);
        assertThat(maxTtl - minTtl).isLessThanOrEqualTo(2);
    }

    /*
     * 대응 방식: TTL에 랜덤 오프셋을 섞어서 만료 시점을 다양하게 설정
     * 일반적으로 가장 많이 쓰이는 방법(효과 대비 구현 비용이 낮음)
     * 장점: 만료 시점 분산으로 트래픽 스파이크 완화
     * 단점: 만료 시점이 랜덤이라 예측/분석이 조금 어려움
     *
     * 이 테스트는 TTL이 서로 다른 값으로 분산되는지 확인
     * Jitter 뜻: 신호의 주기, 주파수, 위상, 듀티 사이클, 또는 다른 타이밍 특성 등의 불안정성
     */
    @Test
    @DisplayName("Jitter 방식으로 키마다 TTL 랜덤")
    void randomJitter_spreadsExpiration() {
        // 준비: 키를 대량으로 만들어 랜덤 Jitter로 저장
        List<String> symbols = generateSymbols("AVR", dataSize());
        for (String symbol : symbols) {
            quoteCacheService.cacheQuoteWithRandomJitter(symbol, CoinQuote.empty(symbol));
        }

        // 실행: 각 키의 TTL을 조회
        Set<Long> uniqueTtls = new HashSet<>();
        for (String symbol : symbols) {
            uniqueTtls.add(redisTemplate.getExpire("quotes:" + symbol, TimeUnit.SECONDS));
        }

        // 검증: TTL이 하나로 몰리지 않고 퍼졌는지 확인
        assertThat(uniqueTtls.size()).isGreaterThan(1);
    }

    /*
     * 대응 방식: 키 해시로 TTL 오프셋을 고정해 "예측 가능한 분산"을 만듬
     * 일반적으로 랜덤 Jitter보다 덜 쓰이지만, 재현성이 중요한 경우 유용함
     * 장점: 키별 TTL이 고정되어 분석/디버깅에 유리
     * 단점: 해시 분포가 치우치면 일부 구간에 만료가 몰릴 수 있음
     *
     * 이 테스트는 TTL이 분산되면서도 키별로 안정적인지 확인
     */
    @Test
    @DisplayName("해시 Jitter 방식은 키마다 고정된 TTL을 지정")
    void hashJitter_spreadsExpirationDeterministically() {
        // 준비: 키 해시 기반 Jitter로 저장
        List<String> symbols = generateSymbols("AVH", dataSize());
        for (String symbol : symbols) {
            quoteCacheService.cacheQuoteWithHashJitter(symbol, CoinQuote.empty(symbol));
        }

        // 실행: TTL 확인
        Set<Long> uniqueTtls = new HashSet<>();
        for (String symbol : symbols) {
            uniqueTtls.add(redisTemplate.getExpire("quotes:" + symbol, TimeUnit.SECONDS));
        }

        // 검증: TTL이 분산되어 있는지 확인
        assertThat(uniqueTtls.size()).isGreaterThan(1);
    }

    /*
     * 대응 방식: TTL을 없애고(또는 매우 길게) 변경 이벤트로만 갱신
     * 일반적으로는 이벤트/CDC 파이프라인이 있을 때만 사용
     * 장점: 만료로 인한 애벌랜치가 원천적으로 사라짐
     * 단점: 갱신 이벤트 누락 시 스테일 데이터가 오래 남을 수 있음
     *
     * 이 테스트는 TTL이 -1(만료 없음)으로 남는지 확인
     */
    @Test
    @DisplayName("TTL을 설정하지 않거나 만료가 긴 경우")
    void noExpire_pushRefresh() {
        // 준비: TTL 없이 캐시에 저장
        List<String> symbols = generateSymbols("AVP", dataSize());
        for (String symbol : symbols) {
            quoteCacheService.cacheQuoteWithoutTtl(symbol, CoinQuote.empty(symbol));
        }

        // 실행: TTL 값 확인
        Set<Long> uniqueTtls = new HashSet<>();
        for (String symbol : symbols) {
            uniqueTtls.add(redisTemplate.getExpire("quotes:" + symbol, TimeUnit.SECONDS));
        }

        // 검증: 만료 없음(-1) 상태인지 확인
        assertThat(uniqueTtls).containsOnly(-1L);
    }
}
