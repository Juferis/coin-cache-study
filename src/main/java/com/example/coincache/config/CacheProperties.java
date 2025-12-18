package com.example.coincache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "cache.quotes")
public class CacheProperties {

    /**
     * 기본 TTL (초)
     */
    private int baseTtlSeconds = 60;

    /**
     * TTL Jitter 범위 (초) - Avalanche 방지
     */
    private int ttlJitterSeconds = 10;

    /**
     * 분산 락 타임아웃 (밀리초)
     */
    private int lockTimeoutMs = 100;

    /**
     * Null 캐시 TTL (초) - Penetration 방지
     */
    private int nullCacheTtlSeconds = 30;
}
