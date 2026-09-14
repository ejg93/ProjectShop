package com.projectshop.shop.support;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 조용히 위험해지는 설정 둘을 기동에서 막는다(`Q44`, `D14`).
 *
 * <p>{@code Q37} 이 호스팅 전제를 환경변수로 풀면서 <b>기본값을 안전하지 않은 쪽</b>에 뒀고
 * 안내는 {@code backend/README.md} 표에만 있었다. 값이 잘못되면 아무것도 안 깨지고
 * <b>조용히 위험해진다</b> — 세션 쿠키가 평문으로 흐르거나, 아무나 IP 를 속인다.
 *
 * <h2>「지금이 운영인가」를 묻지 않는다</h2>
 *
 * <p>{@code Q37} 이 <b>프로필로 안 가르기로</b> 정했다(`D16`) — env 기본값이 로컬과 같아야
 * 「로컬에선 되는데 배포에선 안 되는」 자리가 안 생긴다. 그래서 환경을 판정하는 대신
 * <b>어느 환경에서도 정당한 이유가 없는 값</b>만 막는다.
 *
 * <table>
 *   <caption>막는 둘</caption>
 *   <tr><th>무엇</th><th>왜 어느 환경에서도 안 되나</th></tr>
 *   <tr><td>신뢰 프록시가 <b>아무 주소나</b> 문다</td>
 *       <td>그러면 백엔드를 직접 때리는 누구나 {@code X-Forwarded-For} 한 줄로 IP 를 속인다.
 *           프록시를 신뢰한다는 것은 <b>그 주소만</b> 신뢰한다는 뜻이라, 전부 신뢰하면 뜻이 사라진다</td></tr>
 *   <tr><td>프록시가 <b>루프백 밖</b>에 있는데 세션 쿠키가 평문</td>
 *       <td>프록시가 다른 기계에 있다는 것은 요청이 망을 지난다는 뜻이다.
 *           그 구간에서 쿠키를 주우면 남의 세션으로 들어간다</td></tr>
 * </table>
 *
 * <p><b>로컬은 안 걸린다.</b> 기본값이 루프백만 신뢰해서 둘 다 해당하지 않는다.
 *
 * <h2>글자가 아니라 동작으로 잰다</h2>
 *
 * <p>{@code .*} 같은 <b>모양을 찾지 않는다.</b> 같은 뜻을 쓰는 방법이 여럿이라 목록이 곧 낡는다.
 * 대신 정규식에 <b>실제 주소를 넣어 본다</b> — 공인 IP 를 물면 전부 신뢰하는 것이고,
 * 사설 IP 를 물면 프록시가 다른 기계에 있는 것이다.
 */
@Component
final class SecuritySettingsCheck {

    /** 공인 IP. 신뢰 프록시가 이것을 물면 사실상 전부 신뢰한다 */
    private static final List<String> PUBLIC_SAMPLES = List.of("203.0.113.7", "8.8.8.8");

    /** 사설 IP. 이것을 물면 프록시가 루프백 밖(다른 기계)에 있다 */
    private static final List<String> PRIVATE_SAMPLES = List.of("10.0.0.5", "192.168.1.7", "172.16.0.9");

    private final String internalProxies;
    private final boolean cookieSecure;

    SecuritySettingsCheck(
            @Value("${server.tomcat.remoteip.internal-proxies}") String internalProxies,
            @Value("${server.servlet.session.cookie.secure}") boolean cookieSecure) {
        this.internalProxies = internalProxies;
        this.cookieSecure = cookieSecure;
    }

    /**
     * <b>생성자에서 안 던진다.</b> 던지면 반쯤 만들어진 객체가 남아서 SpotBugs 의
     * {@code CT_CONSTRUCTOR_THROW} 가 막는다. 빈이 다 만들어진 뒤에 잰다 —
     * 스프링은 이 단계에서 던져도 컨텍스트를 못 띄우므로 <b>막는 힘은 같다.</b>
     */
    @PostConstruct
    void check() {
        verify(internalProxies, cookieSecure);
    }

    /**
     * 못 뜨게 할 조합이면 던진다.
     *
     * <p>정적 메서드인 것은 <b>기동 없이 재기 위해서</b>다 — 이 판정 자체는 스프링을 안 탄다.
     */
    static void verify(String internalProxies, boolean cookieSecure) {
        Pattern proxies;
        try {
            proxies = Pattern.compile(internalProxies);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    "TRUSTED_PROXIES 가 정규식이 아니다: " + internalProxies
                            + ". 못 읽는 값이면 톰캣이 헤더를 아예 안 봐서, 신뢰 프록시를 둔 것이"
                            + " 조용히 없던 일이 된다 (D14)", e);
        }

        if (PUBLIC_SAMPLES.stream().anyMatch(ip -> proxies.matcher(ip).matches())) {
            throw new IllegalStateException(
                    "TRUSTED_PROXIES 가 공인 IP 까지 신뢰한다: " + internalProxies
                            + ". 그러면 백엔드를 직접 부르는 누구나 X-Forwarded-For 로 IP 를 속인다"
                            + " — 프록시를 신뢰한다는 말의 뜻이 사라진다 (D14, Q44)."
                            + " 앞단 프록시의 대역만 적는다");
        }

        boolean proxyIsRemote = PRIVATE_SAMPLES.stream().anyMatch(ip -> proxies.matcher(ip).matches());
        if (proxyIsRemote && !cookieSecure) {
            throw new IllegalStateException(
                    "프록시가 루프백 밖에 있는데(" + internalProxies + ")"
                            + " SESSION_COOKIE_SECURE 가 꺼져 있다. 요청이 망을 지나므로 그 구간에서"
                            + " 세션 쿠키를 주우면 남의 세션으로 들어간다 (D14, Q44)."
                            + " SESSION_COOKIE_SECURE=true 로 켠다");
        }
    }
}
