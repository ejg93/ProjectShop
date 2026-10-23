package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 기동 탐침이 시험 컨테이너의 판을 기대값으로 읽는가(`Q297`).
 *
 * <p>컨테이너 판은 {@code PostgresTestBase} 가, 기대값은 {@link StoreVersionCheck} 상수가 든다 — 둘이 갈리면 여기서 빨갛다.
 * 컴포즈와 상수가 같은지는 {@code StackVersionConsistencyTest} 가 따로 잰다.
 */
@DisplayName("저장소 판 탐침")
class StoreVersionCheckTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final Logger logger = (Logger) LoggerFactory.getLogger(StoreVersionCheck.class);

    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("시험 컨테이너의 메이저가 기대값과 같다")
    void containerMatchesExpectedMajors() {
        StoreVersionCheck.Result result = StoreVersionCheck.measure(jdbc, redis);

        assertThat(result.postgresMajor())
                .describedAs("PostgresTestBase 의 이미지를 바꿨으면 StoreVersionCheck.EXPECTED_POSTGRES_MAJOR 도 같이 — %s",
                        result.postgresVersion())
                .isEqualTo(StoreVersionCheck.EXPECTED_POSTGRES_MAJOR);
        assertThat(result.redisMajor())
                .describedAs("PostgresTestBase 의 Redis 이미지를 바꿨으면 EXPECTED_REDIS_MAJOR 도 같이 — %s", result.redisVersion())
                .isEqualTo(StoreVersionCheck.EXPECTED_REDIS_MAJOR);
    }

    @Test
    @DisplayName("기대값과 같으면 INFO 한 줄이고 WARN 이 없다")
    void logsOneInfoLineWithoutWarning() {
        new StoreVersionCheck(jdbc, redis).run(null);

        assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .asString()
                .startsWith("저장소 판 postgres=");
        assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .isEmpty();
    }
}
