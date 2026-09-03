package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계정 상태 열거형이 모르는 값을 어떻게 다루나.
 *
 * <p><b>{@code app_user_status_check} 와의 대조는 {@code EnumConstraintTest} 로 옮겼다</b>(`43a-19`).
 * 열거형마다 옆에 두면 <b>새 열거형이 대조를 안 받는 것을 막는 것이 아무것도 없어서</b>다 —
 * 여기 남기고 저기도 두면 같은 단언이 두 벌이 되고, 다음 사람이 어느 쪽에 더할지를 고르게 된다.
 *
 * <p>대조가 빠졌을 때 <b>여기서 나는 사고는 로그인이 막히는 것</b>이다. 제약에 상태를 하나 더하고
 * enum 을 안 고치면 그 계정을 읽는 순간 {@code of()} 가 터져서 로그인 경로가 통째로 500 이 된다.
 *
 * <p>DB 를 안 쓴다. 대조를 옮기면서 이 클래스에 남은 것이 순수 판정뿐이라
 * {@code PostgresTestBase} 를 뗐다(`D15` — 아래층에서 되는 것을 위층에 두지 않는다).
 */
@DisplayName("계정 상태 목록")
class UserStatusTest {

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
