package com.example.coincache.ha.sentinel;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;
import io.lettuce.core.sentinel.api.sync.RedisSentinelCommands;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis Sentinel 페일오버 흐름을 테스트로 재현
 * - 토폴로지: docker/ha-sentinel (master=6379, replica=6380, sentinel-1~3)
 * - 절차: Sentinel에 연결 → docker로 master 중단 → failover 감시 → 새 master에 SET/GET
 * - 제약: 일부 환경은 host.docker.internal을 해석하지 못하므로 노출 포트로 localhost에 직접 붙임
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.location=classpath:ha/application-sentinel.yml"
)
class SentinelFailoverIT {

    private static final Logger log = LoggerFactory.getLogger(SentinelFailoverIT.class);
    private static final String MASTER_NAME = "mymaster";
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("Sentinel 페일오버 시 replica 승격/쓰기 가능 여부를 검증")
    void replicaIsPromotedWhenMasterStops() throws Exception {
        assumeTrue(isDockerAvailable(), "Docker is required for Sentinel failover test");
        assumeTrue(isSentinelAvailable(), "Sentinel topology (docker/ha-sentinel) must be running");

        // 테스트마다 클린 상태를 위해 compose down/up (이전 실패로 master/replica 뒤집힌 상태 방지)
        resetStack();
        RedisURI sentinelUri = sentinelUri();
        MasterAddress initialMaster;

        // Sentinel과의 세션은 try-with-resources로 관리해 장애 상황에서도 정리
        try (RedisClient sentinelClient = RedisClient.create();
             StatefulRedisSentinelConnection<String, String> sentinelConnection = sentinelClient.connectSentinel(sentinelUri)) {
            // sentinel-1(26379)에 붙어 현재 master 주소를 조회 (Sentinel 자체 접속이 실패하면 테스트 스킵)
            RedisSentinelCommands<String, String> sentinelCommands = sentinelConnection.sync();
            // 기대 시작 상태: master=6379, replica=6380
            initialMaster = masterAddress(sentinelCommands);
            log.info("Current master reported by sentinel: {}:{}", initialMaster.host(), initialMaster.port());
            assertThat(initialMaster.port()).isEqualTo(6379);

            // 장애 유발: docker로 master 컨테이너를 중단 (Sentinel이 장애로 판단)
            int stopExit = runDockerCommand(List.of("docker", "stop", "redis-master"));
            assertThat(stopExit).isZero();

            // Sentinel이 replica(6380)를 새 master로 승격할 때까지 대기
            MasterAddress newMaster = awaitMasterSwitch(sentinelCommands, initialMaster, Duration.ofSeconds(30));
            assertThat(newMaster).isNotEqualTo(initialMaster);
            log.info("Failover complete. New master: {}:{}", newMaster.host(), newMaster.port());

            // 새 master 주소로 직접 SET/GET 검증 (Sentinel이 준 주소 사용)
            String key = "ha:sentinel:failover";
            String expected = "ok";
            RedisURI redisUri = RedisURI.Builder.redis(newMaster.host(), newMaster.port()).build(); // Sentinel이 알려준 새 master 주소
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> trySetAndGet(redisUri, key, expected));
        } finally {
            resetStack();
        }
    }

    private boolean trySetAndGet(RedisURI uri, String key, String expected) {
        // host.docker.internal을 해석 못하는 환경이 있어 노출 포트로 localhost에 직접 접속
        RedisURI clientUri = RedisURI.Builder.redis("localhost", uri.getPort()).build();
        try (RedisClient client = RedisClient.create();
             StatefulRedisConnection<String, String> connection = client.connect(clientUri)) {
            // 승격된 master가 정상 서비스 가능한지 SET/GET으로 검증
            RedisCommands<String, String> commands = connection.sync();
            commands.set(key, expected);
            return expected.equals(commands.get(key));
        } catch (Exception e) {
            log.warn("Retrying Redis SET/GET after failover: {}", e.getMessage());
            return false;
        }
    }

    private boolean isDockerAvailable() {
        CommandResult result = runCommand(List.of("docker", "ps"), Duration.ofSeconds(5));
        if (result.exitCode() != 0) {
            log.warn("Docker is not available (exit={}): {}", result.exitCode(), result.stderr());
            return false;
        }
        return true;
    }

    private boolean isSentinelAvailable() {
        try (RedisClient sentinelClient = RedisClient.create();
             StatefulRedisSentinelConnection<String, String> sentinelConnection = sentinelClient.connectSentinel(sentinelUri())) {
            sentinelConnection.sync().ping();
            masterAddress(sentinelConnection.sync());
            return true;
        } catch (Exception e) {
            log.warn("Sentinel check failed: {}", e.getMessage());
            return false;
        }
    }

    // failover 성공 시점까지 Sentinel이 보고하는 master 주소 변화를 기다린다
    private MasterAddress awaitMasterSwitch(RedisSentinelCommands<String, String> sentinelCommands,
                                            MasterAddress initialMaster,
                                            Duration timeout) {
        // Sentinel이 보고하는 master 주소가 initialMaster와 달라질 때까지 폴링
        // (failover 실패/지연 시 타임아웃으로 테스트 실패)
        return Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> masterAddress(sentinelCommands), address -> !address.equals(initialMaster));
    }

    private MasterAddress masterAddress(RedisSentinelCommands<String, String> sentinelCommands) {
        SocketAddress socketAddress = sentinelCommands.getMasterAddrByName(MASTER_NAME);
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) {
            throw new IllegalStateException("Unexpected sentinel response for master address: " + socketAddress);
        }
        return new MasterAddress(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
    }

    private RedisURI sentinelUri() {
        return RedisURI.Builder.sentinel("localhost", 26379, MASTER_NAME).build();
    }

    private void resetStack() throws Exception {
        // Sentinel 토폴로지를 강제로 초기 상태로 재시작 (master:6379, replica:6380)
        // - sentinel.conf가 read-only라 컨테이너 내에서 동적으로 저장하려 할 때 죽는 이슈 -> /tmp로 복사 실행
        // - host.docker.internal DNS 미지원 환경 대응: 테스트는 localhost:포트로 직접 붙음
        // - 동일 이름 컨테이너가 기존에 있어도 down/up으로 상태를 초기화
        runDockerCommand(List.of("docker", "compose", "-f", "docker/ha-sentinel/docker-compose.yml", "down"));
        runDockerCommand(List.of("docker", "compose", "-f", "docker/ha-sentinel/docker-compose.yml", "up", "-d"));
    }

    // docker compose/redis-cli 등 외부 프로세스를 실행해 stdout/stderr를 수집한다.
    private int runDockerCommand(List<String> command) throws InterruptedException, IOException {
        CommandResult result = runCommand(command, DOCKER_TIMEOUT);
        if (result.exitCode() != 0) {
            log.error("Docker command failed (exit={}): {}\nstdout: {}\nstderr: {}",
                    result.exitCode(), String.join(" ", command), result.stdout(), result.stderr());
        } else if (!result.stdout().isEmpty()) {
            log.info("Docker command output: {}", result.stdout());
        }
        return result.exitCode();
    }

    private CommandResult runCommand(List<String> command, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = null;
        try {
            process = processBuilder.start();
            boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "", "Command timed out: " + String.join(" ", command));
            }
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String readFully(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private record MasterAddress(String host, int port) {
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
