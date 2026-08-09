package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 세션 절대 만료(`D14` 12시간).
 *
 * <p>DB 를 안 쓴다. 필터가 보는 것은 세션의 생성 시각 하나뿐이라
 * 컨테이너를 띄우면 확인할 것이 안 늘고 실행만 느려진다(`D15`).
 */
@DisplayName("세션 절대 만료")
class AbsoluteSessionTimeoutFilterTest {

    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter();

    @Test
    @DisplayName("12시간이 지난 세션을 끊는다")
    void invalidatesOldSession() throws Exception {
        MockHttpSession session = sessionCreatedAgo(Duration.ofHours(13));

        filter.doFilter(requestWith(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid())
                .as("무활동 만료만 두면 계속 쓰는 세션은 영원히 안 끝난다. 탈취되면 그대로 영구다")
                .isTrue();
    }

    @Test
    @DisplayName("아직 12시간이 안 됐으면 그대로 둔다")
    void keepsFreshSession() throws Exception {
        MockHttpSession session = sessionCreatedAgo(Duration.ofHours(11));

        filter.doFilter(requestWith(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("끊을 때 인증 컨텍스트도 비운다")
    void clearsSecurityContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("someone", null, java.util.List.of()));

        filter.doFilter(requestWith(sessionCreatedAgo(Duration.ofHours(13))),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("세션만 버리면 이 요청의 나머지 구간이 아직 인증된 상태로 돈다")
                .isNull();

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("세션이 없으면 아무것도 안 한다")
    void toleratesNoSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession(false))
                .as("비로그인 요청에 세션을 만들어 주면 안 된다")
                .isNull();
    }

    /** {@code getCreationTime()} 은 서블릿이 정하는 값이라 밖에서 못 바꾼다. 그래서 덮어쓴다 */
    private static MockHttpSession sessionCreatedAgo(Duration age) {
        long createdAt = System.currentTimeMillis() - age.toMillis();
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return createdAt;
            }
        };
    }

    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }
}
