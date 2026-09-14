package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 조용히 위험해지는 설정 둘을 기동에서 막는지 본다(`Q44`).
 *
 * <p>판정이 정적 메서드라 <b>기동 없이 잰다</b> — 나쁜 값으로 컨텍스트를 띄워 보려면
 * 그 자체로 못 뜨는 테스트가 되고, 그러면 무엇이 막았는지가 안 남는다.
 */
@DisplayName("위험한 설정 조합")
class SecuritySettingsCheckTest {

    private static final String LOOPBACK = "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1";
    private static final String PRIVATE_RANGE = "10\\.\\d+\\.\\d+\\.\\d+";

    @Test
    @DisplayName("기본값인 루프백은 쿠키가 평문이어도 뜬다")
    void loopbackWithPlainCookieStarts() {
        assertThatCode(() -> SecuritySettingsCheck.verify(LOOPBACK, false))
                .as("로컬이 http 라 기본이 평문이다. 여기서 막으면 로컬이 안 뜬다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("아무 주소나 신뢰하면 안 뜬다")
    void trustingEveryAddressFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify(".*", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공인 IP");
    }

    @Test
    @DisplayName("모양이 달라도 아무 주소나 물면 안 뜬다")
    void trustingEveryAddressInAnotherShapeFails() {
        // 글자로 `.*` 를 찾는 것이 아니라 실제 주소를 넣어 보므로, 같은 뜻의 다른 표기도 걸린다.
        assertThatThrownBy(() -> SecuritySettingsCheck.verify("\\d+\\.\\d+\\.\\d+\\.\\d+", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공인 IP");
    }

    @Test
    @DisplayName("프록시가 루프백 밖인데 쿠키가 평문이면 안 뜬다")
    void remoteProxyWithPlainCookieFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify(PRIVATE_RANGE, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SESSION_COOKIE_SECURE");
    }

    @Test
    @DisplayName("프록시가 루프백 밖이어도 쿠키가 secure 면 뜬다")
    void remoteProxyWithSecureCookieStarts() {
        assertThatCode(() -> SecuritySettingsCheck.verify(PRIVATE_RANGE, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정규식이 아니면 안 뜬다")
    void brokenPatternFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify("10.0.0.[", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정규식이 아니다");
    }
}
