package com.example.coincache.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 코인 시세 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinQuote implements Serializable {

    private String symbol;           // 심볼 (BTC, ETH 등)
    private BigDecimal price;        // 현재 가격
    private BigDecimal change24h;    // 24시간 변동률
    private BigDecimal volume24h;    // 24시간 거래량
    private LocalDateTime updatedAt; // 업데이트 시간

    public static CoinQuote empty(String symbol) {
        return CoinQuote.builder()
                .symbol(symbol)
                .build();
    }

    public boolean isEmpty() {
        return price == null;
    }
}
