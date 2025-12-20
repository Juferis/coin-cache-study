# 🪙 Redis 캐싱 전략 스터디

> 코인 시세 조회 API를 통해 Redis 캐싱 전략과 대규모 트래픽 이슈 대응 방법을 학습합니다.

## 📌 스터디 목표

1. **캐싱 이슈 재현**: 실제 환경에서 발생할 수 있는 캐싱 문제를 테스트 코드로 재현
2. **대응 전략 구현**: 각 이슈에 대한 해결책을 직접 구현하고 검증
3. **트레이드오프 이해**: 각 전략의 장단점과 적용 시점 파악

---

## 🚨 다루는 캐싱 이슈

### 1. Cache Stampede (Thundering Herd)
- **상황**: 핫 키 만료 시점에 동시 요청이 몰림 → 원천 DB/API 과부하
- **대응**:
  - 분산 락 (SET NX PX)으로 갱신 단일화
  - SingleFlight로 인스턴스 내부 중복 요청 합치기
  - Logical Expire + SWR로 stale 응답 제공 + 백그라운드 갱신
- **테스트**: 대량 동시 요청에서 원천 조회 횟수 제한

### 2. Cache Avalanche
- **상황**: 다수 키가 동시에 만료 → 순간적 원천 트래픽 폭증
- **대응**:
  - 랜덤 TTL Jitter로 만료 시점 분산
  - 해시 기반 Jitter로 예측 가능한 분산
  - TTL 제거 + Push 갱신(이벤트 기반)으로 만료 폭발 제거
- **테스트**: TTL 분산 여부/만료 없음(-1) 여부 확인

### 3. Cache Penetration
- **상황**: 존재하지 않는 키 반복 조회 → 캐시 무력화, 원천 직접 호출
- **대응**:
  - 화이트리스트 검증
  - Null Cache(negative cache)
  - Bloom Filter로 사전 차단
- **테스트**: 잘못된 키 요청 시 원천 미조회/오탐 수준 확인

---

## 🏗️ 프로젝트 구조

```
src/main/java/com/example/coincache/
├── cache/
│   ├── BloomFilter.java         # Penetration 방지용 Bloom Filter
│   └── CacheValue.java          # Logical Expire 캐시 래퍼
├── config/
│   ├── RedisConfig.java         # Redis 설정
│   └── CacheProperties.java     # 캐시 설정값 (TTL, Jitter 등)
├── domain/
│   └── CoinQuote.java           # 코인 시세 도메인
├── repository/
│   └── InMemoryCoinQuoteRepository.java  # 원천 데이터 (테스트용)
├── service/
│   └── QuoteCacheService.java   # 캐싱 전략 핵심 로직
└── controller/
    └── QuoteController.java     # REST API

src/test/java/com/example/coincache/
├── support/
│   └── CacheTestSupport.java
└── service/
    ├── CacheStampedeTest.java
    ├── CacheAvalancheTest.java
    └── CachePenetrationTest.java
```

---

## 🧪 테스트 실행

```bash
./gradlew test
```

대용량 크기 조절(기본 10,000):
```bash
./gradlew test -Dtest.data.size=5000
```

### 테스트 시나리오
| 테스트            | 검증 내용                                |
|----------------|--------------------------------------|
| Stampede 방지    | 분산 락/SingleFlight/Logical Expire 비교  |
| Penetration 방지 | 화이트리스트/Null Cache/Bloom Filter 비교    |
| Avalanche 방지   | 고정 TTL vs 랜덤/해시 Jitter vs TTL 없음     |

---

## ⚙️ 기술 스택

- Java 17
- Spring Boot 3.2
- Spring Data Redis (Lettuce)
- Local Redis (테스트 실행 시 필요)
- JUnit 5

---

## 🔧 설정값

```yaml
cache:
  quotes:
    base-ttl-seconds: 60        # 기본 TTL
    ttl-jitter-seconds: 10      # TTL 랜덤 범위 (Avalanche 방지)
    lock-timeout-ms: 100        # 분산 락 타임아웃 (Stampede 방지)
    null-cache-ttl-seconds: 30  # Null 캐시 TTL (Penetration 방지)
    logical-expire-seconds: 60  # 논리 만료 (SWR)
    stale-ttl-buffer-seconds: 30 # 논리 만료 버퍼
    refresh-threads: 4          # 논리 만료 갱신 스레드 수
    single-flight-wait-ms: 500  # SingleFlight 대기 시간

repository:
  latency-ms: 50                # 원천 조회 지연(시뮬레이션)

embedded:
  redis:
    enabled: false              # 로컬 Redis 사용
```
