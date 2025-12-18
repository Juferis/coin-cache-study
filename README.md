# 🪙 Redis 캐싱 전략 스터디

> 코인 시세 조회 API를 통해 Redis 캐싱 전략과 대규모 트래픽 이슈 대응 방법을 학습합니다.

## 📌 스터디 목표

1. **캐싱 이슈 재현**: 실제 환경에서 발생할 수 있는 캐싱 문제를 테스트 코드로 재현
2. **대응 전략 구현**: 각 이슈에 대한 해결책을 직접 구현하고 검증
3. **트레이드오프 이해**: 각 전략의 장단점과 적용 시점 파악

---

## 🚨 다루는 캐싱 이슈

### 1. Cache Stampede (Thundering Herd)
- **상황**: 캐시 만료 시점에 동시 다발적 요청 → 원천 DB/API 과부하
- **대응**: 분산 락 (SET NX PX) 으로 갱신 요청 단일화
- **테스트**: 50개 동시 요청 시 원천 조회 횟수 검증

### 2. Cache Avalanche  
- **상황**: 다수 키가 동시에 만료 → 순간적 원천 트래픽 폭증
- **대응**: TTL Jitter (랜덤 TTL) 적용으로 만료 시점 분산
- **테스트**: 여러 키의 TTL이 분산되었는지 확인

### 3. Cache Penetration
- **상황**: 존재하지 않는 키 반복 조회 → 캐시 무력화, 원천 직접 호출
- **대응**: 화이트리스트 검증 + Null Cache 저장
- **테스트**: 잘못된 키 요청 시 원천 미조회 확인

---

## 🏗️ 프로젝트 구조

```
src/main/java/com/example/coincache/
├── config/
│   ├── RedisConfig.java        # Redis 설정
│   └── CacheProperties.java    # 캐시 설정값 (TTL, Jitter 등)
├── domain/
│   └── CoinQuote.java          # 코인 시세 도메인
├── repository/
│   └── InMemoryCoinQuoteRepository.java  # 원천 데이터 (테스트용)
├── service/
│   └── QuoteCacheService.java  # ⭐ 캐싱 전략 핵심 로직
└── controller/
    └── QuoteController.java    # REST API
```

---

## 🧪 테스트 실행

```bash
./gradlew test
```

### 테스트 시나리오
| 테스트 | 검증 내용 |
|--------|-----------|
| Cache-Aside 기본 | Cache Hit/Miss 동작 확인 |
| Stampede 방지 | 동시 요청 시 원천 조회 최소화 |
| Penetration 방지 | 잘못된 키 차단 + Null 캐시 |
| Avalanche 방지 | TTL 분산 확인 |
| TTL 만료 | 만료 후 재조회 동작 |

---

## ⚙️ 기술 스택

- Java 17
- Spring Boot 3.2
- Spring Data Redis (Lettuce)
- Embedded Redis (테스트용)
- JUnit 5 + Awaitility

---

## 🔧 설정값

```yaml
cache:
  quotes:
    base-ttl-seconds: 60      # 기본 TTL
    ttl-jitter-seconds: 10    # TTL 랜덤 범위 (Avalanche 방지)
    lock-timeout-ms: 100      # 분산 락 타임아웃 (Stampede 방지)
    null-cache-ttl-seconds: 30 # Null 캐시 TTL (Penetration 방지)
```

---
