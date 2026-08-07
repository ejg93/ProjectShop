package com.projectshop.shop.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HttpBasicConfigurer;
import org.springframework.security.config.annotation.web.configurers.FormLoginConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증의 바탕. 무엇이 열려 있고 인증이 어떤 형태로 실패하는지를 한 군데서 정한다.
 *
 * <p>회원가입·로그인 엔드포인트는 여기 없다. 이 청크는 <b>기반만</b> 깐다.
 * 엔드포인트가 생기는 청크가 아래 {@code permitAll} 목록에 자기 경로를 넣는다.
 */
@Configuration
public class SecurityConfig {

    /**
     * 인증 없이 열어 두는 경로. 새 경로를 여기 넣는 것은 <b>명시적인 결정</b>이어야 한다.
     *
     * <p>기본이 열림이면 새 엔드포인트가 아무도 모르는 채로 공개된다.
     * 그래서 아래 필터 체인의 기본값은 {@code authenticated} 고, 예외만 이 목록에 적는다.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/health",
            "/actuator/health",
            "/actuator/health/**",
            // 가입은 계정이 없는 사람이 부른다. 잠그면 아무도 가입할 수 없다.
            "/api/auth/signup");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())

                // 폼 로그인과 HTTP Basic 을 끈다.
                //
                // 켜 두면 인증이 없을 때 401 이 아니라 로그인 페이지로 302 가 나간다.
                // JSON 을 기대하는 클라이언트는 302 를 따라가서 HTML 을 받고,
                // 실패 원인이 "인증 없음" 이 아니라 "응답이 JSON 이 아님" 으로 보인다.
                .formLogin(FormLoginConfigurer::disable)
                .httpBasic(HttpBasicConfigurer::disable)

                // 인증이 없으면 401 만 준다. 본문 형식(RFC 9457)은 청크 7b 가 정한다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .sessionManagement(session -> session
                        // 세션은 필요할 때만 만든다. 열린 경로를 훑는 것만으로 세션이 쌓이지 않게 한다.
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 로그인 시 세션 ID 재발급(D14). Spring 기본값과 같지만 명시한다 —
                        // 기본값에 기대면 이 요구사항이 코드 어디에도 안 보인다.
                        .sessionFixation(config -> config.changeSessionId()));

        // CSRF 토큰을 쿠키로 내려준다.
        //
        // 기본 저장소는 세션이라 클라이언트가 토큰을 얻을 방법이 아예 없다.
        // 5-2 를 끝내고 curl 로 가입을 걸어 보니 401 이었다 — 테스트는 with(csrf()) 로
        // 우회하기 때문에 이 벽이 안 드러났다.
        //
        // 쿠키는 HttpOnly 가 아니어야 한다. 스크립트가 읽어서 헤더에 실어야 하기 때문이다.
        // 세션 쿠키와 목적이 다르다 — 이쪽은 읽히는 것이 목적이고, 훔쳐도 남의 세션이 되지 않는다.
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    /**
     * 토큰을 한 번 읽어서 쿠키가 나가게 만든다.
     *
     * <p>저장소는 <b>토큰을 읽을 때</b> 쿠키를 심는다. 아무도 안 읽으면 아무것도 안 나간다.
     * 위의 지연 끄기만으로는 부족했다 — 토큰이 만들어져도 읽히지 않으면 응답에 안 실린다.
     * 실제로 이 필터 없이 돌려 보고 쿠키가 안 나오는 것을 봤다.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {

            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * 내보낼 때는 XOR 로 가리고, <b>헤더로 돌아온 값은 평문으로 비교</b>한다.
     *
     * <p>둘을 갈라야 하는 이유가 있다. 저장소는 쿠키에 평문 토큰을 넣는데,
     * XOR 핸들러는 돌아온 값을 인코딩된 것으로 보고 디코딩을 시도한다.
     * 클라이언트가 쿠키에서 읽은 값을 그대로 헤더에 실으면 그 자리에서 깨진다.
     * 실제로 XOR 핸들러만 두고 돌려 보고 이 실패를 봤다.
     *
     * <p>내보낼 때 XOR 를 유지하는 것은 응답 본문에 같은 토큰이 반복해서 실리지 않게 하려는 것이다.
     * 폼 파라미터로 오는 경로는 그대로 XOR 로 푼다 — 그쪽은 서버가 심어 준 값이 돌아온다.
     */
    private static CsrfTokenRequestHandler csrfTokenRequestHandler() {
        return new CsrfTokenRequestAttributeHandler() {

            private final XorCsrfTokenRequestAttributeHandler xor =
                    new XorCsrfTokenRequestAttributeHandler();

            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                    java.util.function.Supplier<CsrfToken> csrfToken) {
                xor.handle(request, response, csrfToken);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
                return StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))
                        ? super.resolveCsrfTokenValue(request, csrfToken)
                        : xor.resolveCsrfTokenValue(request, csrfToken);
            }
        };
    }

    /**
     * 저장값에 {@code {bcrypt}} 접두사가 붙는다(D14).
     *
     * <p>접두사가 알고리즘을 저장값 안에 들고 있어서 나중에 argon2id 로 갈아탈 때
     * 새 비밀번호만 새 알고리즘으로 저장하면 된다. 옛 해시는 그대로 검증되고 일괄 재해시가 없다.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 사용자가 없는 빈 저장소. DB 기반 조회는 로그인 청크가 넣는다.
     *
     * <p>이 빈이 없으면 Boot 가 자동으로 {@code user} 계정을 만들고 무작위 비밀번호를
     * 기동 로그에 찍는다. 쓸 데도 없는 계정이 뜨고, 비밀번호가 로그에 남는다.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
