package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 기동 탐침이 이 DB 의 검색 로케일을 재서 한 줄 남기는지 본다(`Q246`).
 *
 * <p>컨테이너(`postgres:18-alpine`)는 {@code en_US.utf8} 이라 한글이 조각이 된다 — 운영 값은 기동 로그가 든다.
 */
@DisplayName("검색 로케일 탐침")
class SearchLocaleCheckTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private final Logger logger = (Logger) LoggerFactory.getLogger(SearchLocaleCheck.class);
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
    @DisplayName("컨테이너 DB 는 한글을 조각으로 낸다")
    void containerYieldsHangulTrigrams() {
        SearchLocaleCheck.Result result = SearchLocaleCheck.measure(jdbc);

        assertThat(result.ctype()).isNotBlank();
        assertThat(result.hangulTrigrams())
                .as("0 이면 V123 인덱스가 한글 검색을 못 거른다")
                .isPositive();
    }

    @Test
    @DisplayName("잰 값을 INFO 한 줄로 남기고 경고는 안 낸다")
    void logsOneInfoLine() {
        new SearchLocaleCheck(jdbc).run(null);

        assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .startsWith("검색 로케일 datctype=");
        assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }
}
