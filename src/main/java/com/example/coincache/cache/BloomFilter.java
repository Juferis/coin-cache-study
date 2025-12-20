package com.example.coincache.cache;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.Collection;

/**
 * 간단한 Bloom Filter 구현 (읽기 다중 스레드 용도)
 */
public class BloomFilter implements Serializable {

    private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    });

    private final BitSet bits;
    private final int bitSize;
    private final int numHashFunctions;

    public BloomFilter(int expectedInsertions, double fpp) {
        int safeExpected = Math.max(1, expectedInsertions);
        double safeFpp = Math.min(0.5d, Math.max(0.0001d, fpp));
        this.bitSize = (int) Math.ceil(-safeExpected * Math.log(safeFpp) / (Math.log(2) * Math.log(2)));
        this.numHashFunctions = Math.max(1,
                (int) Math.round((double) bitSize / safeExpected * Math.log(2)));
        this.bits = new BitSet(bitSize);
    }

    public static BloomFilter from(Collection<String> values, double fpp) {
        BloomFilter filter = new BloomFilter(values.size(), fpp);
        for (String value : values) {
            filter.put(value);
        }
        return filter;
    }

    public void put(String value) {
        long[] hashes = hash128(value);
        long hash1 = hashes[0];
        long hash2 = hashes[1];
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = hash1 + (long) i * hash2;
            int index = (int) ((combined & Long.MAX_VALUE) % bitSize);
            bits.set(index);
        }
    }

    public boolean mightContain(String value) {
        long[] hashes = hash128(value);
        long hash1 = hashes[0];
        long hash2 = hashes[1];
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = hash1 + (long) i * hash2;
            int index = (int) ((combined & Long.MAX_VALUE) % bitSize);
            if (!bits.get(index)) {
                return false;
            }
        }
        return true;
    }

    private long[] hash128(String value) {
        MessageDigest md = MD5.get();
        md.reset();
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        return new long[]{buffer.getLong(), buffer.getLong()};
    }
}
