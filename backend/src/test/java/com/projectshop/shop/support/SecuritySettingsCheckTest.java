package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
    /** 견본 목록에 없는 공인 주소. 로드밸런서 하나에 맞춘 배포가 이 꼴이다 */
    private static final String PUBLIC_PINNED = "52\\.1\\.2\\.3";

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


    /**
     * <b>견본에 없는 공인 주소도 루프백 밖이다.</b> 처음에는 사설 대역 견본만 넣어 봐서,
     * 프록시를 로드밸런서 하나에 맞춘 배포가 <b>그물 두 개 사이로 빠졌다</b>(마무리 14차 리뷰).
     */
    @Test
    @DisplayName("공인 주소 하나에 맞춘 프록시도 쿠키가 평문이면 안 뜬다")
    void pinnedPublicProxyWithPlainCookieFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify(PUBLIC_PINNED, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SESSION_COOKIE_SECURE");
    }

    @Test
    @DisplayName("빈 값이면 안 뜬다")
    void blankProxiesFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify("   ", true))
                .as("빈 값이면 이 검사가 아무것도 안 물어서 통과하는데, 서버가 쓰는 값은 그것이 아니다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비었다");
    }
    @Test
    @DisplayName("정규식이 아니면 안 뜬다")
    void brokenPatternFails() {
        assertThatThrownBy(() -> SecuritySettingsCheck.verify("10.0.0.[", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정규식이 아니다");
    }
    /**
     * <b>「기동에서 막는다」를 기동으로 잰다</b>(`Q47`).
     *
     * <p>위 테스트들은 {@link SecuritySettingsCheck#verify} 를 직접 부른다 — <b>판정은 재지만
     * 그 판정이 기동에 걸려 있다는 것은 안 잰다.</b> {@code @PostConstruct} 를 떼거나
     * {@code @Value} 키를 오타 내도 저 여덟이 전부 초록이다(마무리 14차 리뷰가 짚었다).
     *
     * <p><b>{@code @SpringBootTest} 를 안 쓴다.</b> 나쁜 값을 {@code properties} 로 주면
     * 캐시 키가 달라져서 <b>컨텍스트가 하나 더 뜬다</b> — `D15` 가 「컨텍스트 가짓수가 곧 기동
     * 횟수다」로 재 둔 자리고, 지금 둘이다. {@code ApplicationContextRunner} 는 이 빈만 올린
     * 작은 컨텍스트를 그 자리에서 만들고 버려서 <b>캐시에 안 들어간다.</b>
     */
    @Test
    @DisplayName("나쁜 값이면 컨텍스트가 안 뜬다")
    void contextFailsOnDangerousValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecuritySettingsCheck.class)
                .withPropertyValues(
                        "server.tomcat.remoteip.internal-proxies=.*",
                        "server.servlet.session.cookie.secure=true")
                .run(context -> assertThat(context)
                        .as("판정이 기동에 안 걸려 있으면 나쁜 값으로도 컨텍스트가 뜬다")
                        .hasFailed()
                        .getFailure()
                        // 메시지는 원인 예외에 있다. 스프링이 「빈을 못 만들었다」로 한 겹 감싼다.
                        .rootCause()
                        .hasMessageContaining("공인 IP"));
    }

    @Test
    @DisplayName("기본값이면 컨텍스트가 뜨고 빈이 등록된다")
    void contextStartsOnDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecuritySettingsCheck.class)
                .withPropertyValues(
                        "server.tomcat.remoteip.internal-proxies=" + LOOPBACK,
                        "server.servlet.session.cookie.secure=false")
                .run(context -> assertThat(context)
                        .as("@Value 키가 하나라도 어긋나면 값을 못 채워서 여기서 죽는다")
                        .hasNotFailed()
                        .hasSingleBean(SecuritySettingsCheck.class));
    }
}
