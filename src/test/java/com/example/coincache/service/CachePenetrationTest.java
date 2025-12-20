package com.example.coincache.service;

import com.example.coincache.cache.BloomFilter;
import com.example.coincache.support.CacheTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Cache Penetration (캐시 관통)
 * - 상황: 존재하지 않는 키 요청이 반복되며 캐시를 우회, 원천 데이터에 계속 접근
 * - 실제로 터지는 지점: 원천 DB/API 호출 증가, 불필요한 비용/지연 상승
 * - 핵심 원인: 잘못된 키를 빠르게 걸러낼 장치가 없음
 * - 대응 선택지: 화이트리스트, Null Cache, Bloom Filter
 *
 * "잘못된 키 대량 유입" 상황에서 원천 조회가 얼마나 줄어드는지 확인하는 테스트
 */
@DisplayName("Cache Penetration 대응 테스트")
class CachePenetrationTest extends CacheTestSupport {

    /*
     * 대응 방식: 유효 심볼 목록을 미리 보유하고 즉시 차단
     * 일반적으로 심볼/상품/카테고리가 명확할 때 가장 많이 씀
     * 장점: 정확도 100%, 원천 호출 차단 확실
     * 단점: 목록을 항상 최신으로 유지해야 함
     *
     * 원천 조회가 0인지로 방어 효과를 확인
     */
    @Test
    @DisplayName("화이트리스트: 유효하지 않은 심볼을 즉시 차단")
    void whitelist_blocksInvalidSymbols() {
        // 준비: 존재하지 않는 심볼을 대량으로 만들기
        List<String> invalidSymbols = generateSymbols("BAD", dataSize());

        // 실행: 잘못된 심볼로 반복 요청을 발생
        for (String symbol : invalidSymbols) {
            quoteCacheService.getQuote(symbol);
        }

        // 검증: 원천 조회가 발생하지 않았는지 확인
        assertThat(repository.getQueryCount()).isEqualTo(0);
    }

    /*
     * 대응 방식: "없다"는 결과를 짧게 캐싱(negative cache)
     * 일반적으로 많이 쓰이는 방식
     * 장점: 동일한 미스 반복을 차단
     * 단점: TTL 동안 실제로 생긴 데이터도 "없음"으로 보일 수 있음
     *
     * 동일 미스가 1회만 원천 조회되는지 확인
     */
    @Test
    @DisplayName("Null Cache: 존재하지만 데이터가 없는 키에 대해 반복 조회를 막음")
    void nullCache_preventsRepeatedMisses() {
        // 준비: 화이트리스트에는 있지만 실제 데이터는 없는 키를 만들기
        String missingSymbol = "MISS001";
        repository.addValidSymbolOnly(missingSymbol);

        // 실행: 같은 키를 대량으로 반복 요청
        int requests = Math.min(5000, dataSize());
        for (int i = 0; i < requests; i++) {
            quoteCacheService.getQuote(missingSymbol);
        }

        // 검증: 첫 요청만 원천 데이터 요청을 하고 이후는 null 캐시로 막혔는지 확인
        assertThat(repository.getQueryCount()).isEqualTo(1);
    }

    /*
     * 대응 방식: Bloom Filter로 "존재 가능성"이 낮은 요청을 사전 차단
     * 일반적으로 키가 매우 많을 때(대규모) 주로 사용
     * 참고: Facebook Engineering (Scalable Bloom Filters)
     * 장점: 메모리 효율적, 대량 키에서도 빠른 필터링
     * 단점: False Positive로 일부 원천 조회가 남을 수 있음
     *
     * 허용된 원천 데이터 조회수를 넘지 않는지 확인
     */
    @Test
    @DisplayName("Bloom Filter: 대부분의 잘못된 요청을 사전에 걸러냄")
    void bloomFilter_blocksMostInvalidRequests() {
        // 준비: 대량 유효 키를 시드로 넣어 Bloom Filter를 구성
        List<String> validSymbols = seedSymbols(dataSize(), "VAL");
        BloomFilter bloomFilter = BloomFilter.from(validSymbols, 0.01d);
        repository.resetQueryCount();

        // 실행: 존재하지 않는 키를 대량으로 던져 필터링 효과를 확인
        List<String> invalidSymbols = generateSymbols("BAD", dataSize());
        for (String symbol : invalidSymbols) {
            quoteCacheService.getQuoteWithSymbolFilter(symbol, bloomFilter::mightContain);
        }

        // 검증: 잘못된 조회 비율 수준으로만 원천 조회가 발생했는지 확인
        int allowed = (int) (dataSize() * 0.03) + 5;
        assertThat(repository.getQueryCount()).isLessThanOrEqualTo(allowed);
    }
}
