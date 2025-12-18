package com.example.coincache.repository;

import com.example.coincache.domain.CoinQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 인메모리 원천 저장소
 * 실제 환경에서는 외부 API 호출로 대체
 */
@Slf4j
@Repository
public class InMemoryCoinQuoteRepository implements CoinQuoteRepository {

    // 지원하는 심볼 화이트리스트
    private static final Set<String> VALID_SYMBOLS = Set.of(
            "BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "AVAX", "DOT"
    );

    // 원천 데이터 (시뮬레이션)
    private final Map<String, CoinQuote> dataStore = new ConcurrentHashMap<>();

    // 원천 조회 카운터 (테스트용 - 캐시 스탬피드 확인)
    private final AtomicInteger queryCount = new AtomicInteger(0);

    public InMemoryCoinQuoteRepository() {
        initializeData();
    }

    private void initializeData() {
        dataStore.put("BTC", createQuote("BTC", "67500.00", "2.5", "28000000000"));
        dataStore.put("ETH", createQuote("ETH", "3650.00", "1.8", "15000000000"));
        dataStore.put("XRP", createQuote("XRP", "0.52", "-0.5", "1200000000"));
        dataStore.put("SOL", createQuote("SOL", "145.00", "5.2", "3500000000"));
        dataStore.put("DOGE", createQuote("DOGE", "0.12", "0.3", "800000000"));
        dataStore.put("ADA", createQuote("ADA", "0.45", "-1.2", "450000000"));
        dataStore.put("AVAX", createQuote("AVAX", "35.50", "3.1", "620000000"));
        dataStore.put("DOT", createQuote("DOT", "7.20", "0.8", "320000000"));
    }

    private CoinQuote createQuote(String symbol, String price, String change, String volume) {
        return CoinQuote.builder()
                .symbol(symbol)
                .price(new BigDecimal(price))
                .change24h(new BigDecimal(change))
                .volume24h(new BigDecimal(volume))
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public Optional<CoinQuote> findBySymbol(String symbol) {
        int count = queryCount.incrementAndGet();
        log.info("[원천 조회] symbol={}, 총 조회 횟수={}", symbol, count);

        // 원천 조회 지연 시뮬레이션 (50ms)
        simulateLatency();

        CoinQuote quote = dataStore.get(symbol);
        if (quote != null) {
            // 조회 시점 업데이트 (기존 데이터 복사 + updatedAt만 변경)
            quote = CoinQuote.builder()
                    .symbol(quote.getSymbol())
                    .price(quote.getPrice())
                    .change24h(quote.getChange24h())
                    .volume24h(quote.getVolume24h())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
        return Optional.ofNullable(quote);
    }

    @Override
    public boolean existsSymbol(String symbol) {
        return VALID_SYMBOLS.contains(symbol);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 테스트 헬퍼 메서드
    public int getQueryCount() {
        return queryCount.get();
    }

    public void resetQueryCount() {
        queryCount.set(0);
    }

    public void updateQuote(String symbol, CoinQuote quote) {
        dataStore.put(symbol, quote);
    }
}
