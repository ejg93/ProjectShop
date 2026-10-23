package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장소 판 탐침이 분기마다 맞는 경고를 내는가(`Q297`, PR #109 리뷰).
 *
 * <p>컨테이너에 닿는 쪽은 {@code StoreVersionCheckTest}(느린 레인)가 잰다. 여기는 판정만 — 잰 값을 손으로 넣는다.
 * <b>못 읽은 것과 다른 것이 갈리는지</b>가 요점이다. 못 읽은 것을 「다르다」로 적으면 실제 갈림과 구별이 안 된다.
 */
@DisplayName("저장소 판 경고")
class StoreVersionWarningsTest {

    private static final String SAME_REDIS = StoreVersionCheck.EXPECTED_REDIS_MAJOR + ".2.1";

    @Test
    @DisplayName("둘 다 기대값이면 경고가 없다")
    void sameMajorsWarnNothing() {
        var result = new StoreVersionCheck.Result("18.1", StoreVersionCheck.EXPECTED_POSTGRES_MAJOR, SAME_REDIS);

        assertThat(StoreVersionCheck.warnings(result)).isEmpty();
    }

    @Test
    @DisplayName("Postgres 메이저가 다르면 「다르다」 하나")
    void postgresMismatchWarnsOnce() {
        var result = new StoreVersionCheck.Result("17.6", StoreVersionCheck.EXPECTED_POSTGRES_MAJOR - 1, SAME_REDIS);

        assertThat(StoreVersionCheck.warnings(result)).singleElement().asString().startsWith("저장소 판이 시험과 다르다");
    }

    @Test
    @DisplayName("Redis 메이저가 다르면 「다르다」 하나")
    void redisMismatchWarnsOnce() {
        var result = new StoreVersionCheck.Result("18.1", StoreVersionCheck.EXPECTED_POSTGRES_MAJOR, "7.4.6");

        assertThat(StoreVersionCheck.warnings(result)).singleElement().asString().startsWith("저장소 판이 시험과 다르다");
    }

    @Test
    @DisplayName("Redis 판을 못 읽으면 「못 읽었다」만 — 「다르다」로 안 센다")
    void unreadRedisIsNotMismatch() {
        var result = new StoreVersionCheck.Result("18.1", StoreVersionCheck.EXPECTED_POSTGRES_MAJOR, null);

        assertThat(StoreVersionCheck.warnings(result)).singleElement().asString().startsWith("Redis 판을 못 읽었다");
    }
}
