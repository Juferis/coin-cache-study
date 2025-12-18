package com.example.coincache.repository;

import com.example.coincache.domain.CoinQuote;

import java.util.Optional;

/**
 * 원천 데이터 저장소 인터페이스
 * 실제로는 외부 API 호출 또는 DB 조회
 */
public interface CoinQuoteRepository {

    /**
     * 원천에서 시세 조회
     * @param symbol 코인 심볼
     * @return 시세 정보 (없으면 empty)
     */
    Optional<CoinQuote> findBySymbol(String symbol);

    /**
     * 심볼 존재 여부 확인 (화이트리스트 체크)
     */
    boolean existsSymbol(String symbol);
}
