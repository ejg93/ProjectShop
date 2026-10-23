package com.projectshop.shop.support;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 기동 때 한 번, 붙은 Postgres·Redis 의 판을 재서 로그에 남긴다(`Q297`).
 *
 * <p><b>시험이 보는 판과 운영 판이 갈린 채 아무도 몰랐다.</b> 2026-10-23 점검 W 가 운영 Postgres 18·Redis 8.2 를
 * 찾았을 때 컴포즈·Testcontainers 는 17·7 이었다 — 시험이 초록이어도 운영의 판에서 초록이라는 보장이 없었다.
 * 운영 저장소에 직접 닿는 길이 없어서(TCP 프록시 없음) {@link SearchLocaleCheck} 처럼 <b>모든 환경이 스스로 보고한다.</b>
 *
 * <p><b>기대 메이저는 시험이 묶는다.</b> 아래 두 상수를 {@code StackVersionConsistencyTest} 가 컴포즈 이미지 태그와 견준다 —
 * 컴포즈·시험을 올리고 이 값을 안 고치면 빠른 레인이 빨갛고, 운영만 판이 바뀌면 기동 WARN 이 그 신호다.
 *
 * <p><b>기동은 안 막는다.</b> 판이 다르다고 쇼핑몰이 안 뜰 이유는 없다. 로그에는 판 글자만 나간다(`D16`).
 */
@Component
final class StoreVersionCheck implements ApplicationRunner {

    /** 컴포즈 `db` 이미지(`postgres:18-alpine`)의 메이저 */
    static final int EXPECTED_POSTGRES_MAJOR = 18;

    /** 컴포즈 `redis` 이미지(`redis:8.2-alpine`)의 메이저 */
    static final int EXPECTED_REDIS_MAJOR = 8;

    private static final Logger log = LoggerFactory.getLogger(StoreVersionCheck.class);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    StoreVersionCheck(JdbcClient jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /** 잰 값. Redis 판을 못 읽으면 {@code redisVersion} 이 빈다 */
    record Result(String postgresVersion, int postgresMajor, String redisVersion) {

        /** {@code 8.2.1} 의 앞 칸. 못 읽었으면 -1 */
        int redisMajor() {
            if (redisVersion == null || redisVersion.isBlank()) {
                return -1;
            }
            try {
                return Integer.parseInt(redisVersion.split("\\.")[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result;
        try {
            result = measure(jdbc, redis);
        } catch (DataAccessException e) {
            log.warn("저장소 판을 못 쟀다(Q297): {}", e.getMessage());
            return;
        }
        log.info("저장소 판 postgres={} redis={}", result.postgresVersion(),
                result.redisVersion() == null ? "못 읽음" : result.redisVersion());
        if (result.postgresMajor() != EXPECTED_POSTGRES_MAJOR || result.redisMajor() != EXPECTED_REDIS_MAJOR) {
            log.warn("저장소 판이 시험과 다르다 — 기대 postgres {}·redis {}. docker-compose.yml·PostgresTestBase 와 견준다(Q297)",
                    EXPECTED_POSTGRES_MAJOR, EXPECTED_REDIS_MAJOR);
        }
    }

    /** 기동 없이 재려고 정적으로 둔다 — 판정이 스프링을 안 탄다 */
    static Result measure(JdbcClient jdbc, StringRedisTemplate redis) {
        String version = jdbc.sql("select current_setting('server_version')").query(String.class).single();
        int versionNum = jdbc.sql("select current_setting('server_version_num')::int").query(Integer.class).single();
        String redisVersion;
        try {
            Properties info = redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));
            redisVersion = info == null ? null : info.getProperty("redis_version");
        } catch (DataAccessException e) {
            // 운영 Redis 가 `INFO` 를 권한으로 막을 수 있다 — 판을 못 읽은 것으로 적고 넘어간다(행의 실패 사다리)
            redisVersion = null;
        }
        return new Result(version, versionNum / 10000, redisVersion);
    }
}
