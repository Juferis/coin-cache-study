package com.example.coincache.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 논리 만료를 위한 캐시 래퍼
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheValue<T> implements Serializable {

    private T value;
    private long logicalExpireAtMs;

    public boolean isExpired() {
        return System.currentTimeMillis() > logicalExpireAtMs;
    }
}
