package com.example.coincache.controller;

import com.example.coincache.domain.CoinQuote;
import com.example.coincache.service.QuoteCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 시세 조회 API 컨트롤러
 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteCacheService quoteCacheService;

    @GetMapping("/{symbol}")
    public ResponseEntity<CoinQuote> getQuote(@PathVariable String symbol) {
        Optional<CoinQuote> quote = quoteCacheService.getQuote(symbol.toUpperCase());
        return quote
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{symbol}/refresh")
    public ResponseEntity<Void> refreshCache(@PathVariable String symbol,
                                              @RequestBody CoinQuote quote) {
        quoteCacheService.refreshCache(symbol.toUpperCase(), quote);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{symbol}/cache")
    public ResponseEntity<Void> evictCache(@PathVariable String symbol) {
        quoteCacheService.evictCache(symbol.toUpperCase());
        return ResponseEntity.noContent().build();
    }
}
