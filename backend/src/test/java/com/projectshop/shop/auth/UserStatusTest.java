package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.support.ConstraintValues;

/**
 * 계정 상태 목록이 {@code app_user_status_check} 와 {@link UserStatus} 두 사본이다
 * (`D23` 「목록이 둘로 갈리는 것을 테스트가 막는다」).
 *
 * <p>여기서 어긋나면 <b>로그인이 막힌다.</b> 제약에 상태를 하나 더하고 enum 을 안 고치면
 * 그 계정을 읽는 순간 {@code of()} 가 터져서 로그인 경로가 통째로 500 이 된다.
 */
@DisplayName("계정 상태 목록")
class UserStatusTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("DB 제약과 같다")
    void matchesConstraint() {
        assertThat(ConstraintValues.of(jdbc, "app_user_status_check"))
                .as("한쪽에만 있는 상태가 생기면 로그인이 500 이 되거나 못 쓰는 값이 남는다")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(UserStatus.values()).map(UserStatus::code).toList());
    }

    @Test
    @DisplayName("모르는 값은 조용히 통과하지 않는다")
    void unknownCodeThrows() {
        assertThatThrownBy(() -> UserStatus.of("dormant"))
                .as("""
                        생 문자열 비교는 모르는 값에 false 를 준다 — 그 계정이 이유 없이
                        로그인만 막히고 오류도 로그도 안 남는다. enum 은 그 자리에서 터진다.
                        """)
                .isInstanceOf(IllegalStateException.class);
    }
}
