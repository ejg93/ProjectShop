package com.projectshop.shop.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HttpBasicConfigurer;
import org.springframework.security.config.annotation.web.configurers.FormLoginConfigurer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.SecurityFilterChain;

import com.projectshop.shop.error.ProblemEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.session.ConcurrentSessionFilter;
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
            // 가입과 로그인은 계정이 없거나 아직 인증되지 않은 사람이 부른다.
            "/api/auth/signup",
            "/api/auth/login",
            // 약관·개인정보 고지는 동의하기 전에 읽는 것이다. 그 시점은 로그인 전이다.
            // 막아 두면 가입 화면이 무엇에 동의하는지 못 보여준다(약관규제법 제3조).
            "/api/consent-items/**",
            // 상품 공개 목록. 비로그인도 본다 — 사는 사람은 로그인 전에 물건을 고른다.
            // 이 경로는 판정이 없다. on_sale 만 나가므로 감출 것이 없다(청크 8).
            //
            // 상세(/api/products/{id})는 여기 안 넣는다. 같은 경로에 PUT·DELETE 가 있어서
            // 경로만으로 열면 비로그인이 남의 상품을 고치고 지운다. 아래에서 GET 만 연다.
            "/api/products",
            // 장바구니는 비로그인도 쓴다. 담는 것이 계약 체결 과정의 요청이라
            // 동의 없이 되고(개인정보법 제15조①4호), 주인은 쿠키가 가리킨다(청크 9).
            "/api/cart",
            "/api/cart/**");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, PermissionRuleLoader ruleLoader,
            SessionRegistry sessionRegistry, ProblemEntryPoint entryPoint) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        // 상품 상세는 읽기만 연다(청크 8b). 별 하나라 /{id} 까지만 걸리고
                        // /{id}/approve 같은 검수 경로는 안 걸린다 — 둘 다 필요한 조건이다.
                        .requestMatchers(HttpMethod.GET, "/api/products/*").permitAll()
                        .anyRequest().authenticated())

                // 폼 로그인과 HTTP Basic 을 끈다.
                //
                // 켜 두면 인증이 없을 때 401 이 아니라 로그인 페이지로 302 가 나간다.
                // JSON 을 기대하는 클라이언트는 302 를 따라가서 HTML 을 받고,
                // 실패 원인이 "인증 없음" 이 아니라 "응답이 JSON 이 아님" 으로 보인다.
                .formLogin(FormLoginConfigurer::disable)
                .httpBasic(HttpBasicConfigurer::disable)

                // 인증 실패도 다른 오류와 같은 본문으로 내보낸다(RFC 9457).
                //
                // 이 자리는 MVC 에 닿기 전이라 @RestControllerAdvice 가 못 잡는다.
                // 그래서 본문을 여기서 직접 쓰는데, 만드는 것은 ProblemFactory 하나다 —
                // 두 자리가 각자 만들면 같은 오류가 형태만 다르게 두 벌 나간다.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))

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
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // 인가 직전에 둔다. 인증이 확정된 뒤여야 principal 을 볼 수 있고,
                // 인가 전이어야 죽은 계정이 아무것도 통과하지 못한다.
                .addFilterBefore(new AccountLivenessFilter(ruleLoader), AuthorizationFilter.class);

        // 만료 표시된 세션을 실제로 끊는다.
        //
        // SessionRegistry 의 expireNow() 는 표시만 남긴다. 이 필터가 없으면 탈퇴(5g)가
        // 세션을 끊었다고 믿는데 아무 일도 안 일어난다 — 부른 줄 알았는데 안 먹는 쪽이 제일 나쁘다.
        //
        // 기본 전략은 본문에 안내 문구를 쓴다. 우리는 JSON API 라 401 만 준다.
        http.addFilterAfter(
                new ConcurrentSessionFilter(sessionRegistry,
                        event -> event.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value())),
                SecurityContextHolderFilter.class);

        // 세션을 만든 지 12시간이 지나면 끊는다(D14, 청크 5c).
        //
        // 컨텍스트를 읽은 직후에 둔다. 앞에 두면 아직 SecurityContextHolder 가 안 채워져서
        // 지울 것이 없고, 뒤에 두면 인가가 이미 끝난 뒤라 늙은 세션이 한 요청을 더 통과한다.
        http.addFilterAfter(new AbsoluteSessionTimeoutFilter(), SecurityContextHolderFilter.class);

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
     * 아이디와 비밀번호를 실제로 대조하는 곳.
     *
     * <p>{@code formLogin} 을 껐으므로 이것을 부르는 곳이 없다. 컨트롤러가 직접 부른다.
     */
    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * 로그인이 성공한 뒤에 세션에 해야 할 일들.
     *
     * <p>{@code formLogin} 이 하던 일이다. 껐으므로 <b>아무도 안 부른다</b> —
     * 컨트롤러가 부르지 않으면 세션 고정 방어(`D14`)도 세션 등록도 조용히 빠진다.
     *
     * <p>둘을 묶어 두는 이유는 하나만 부르는 실수를 없애려는 것이다.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                // 세션 ID 를 갈아 공격자가 미리 심어 둔 ID 를 죽인다(D14).
                new ChangeSessionIdAuthenticationStrategy(),
                // 탈퇴(5g)가 이 등록을 보고 세션을 만료시킨다(ADR 0010).
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    /**
     * 인증을 어디에 저장하나. <b>컨트롤러가 이걸 주입받는다.</b>
     *
     * <p>{@code formLogin} 을 껐으므로 로그인 성공 뒤 저장을 컨트롤러가 직접 부른다.
     * 컨트롤러가 {@code new HttpSessionSecurityContextRepository()} 를 자기 안에서 만들면
     * 여기서 저장 방식을 바꿔도 컨트롤러만 옛 방식으로 남는다 — 같은 규칙이 두 군데가 된다.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 누가 어떤 세션을 들고 있는지. 탈퇴·정지가 이걸 보고 세션을 끊는다(`ADR 0010`). */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 세션이 죽었다는 것을 {@link SessionRegistry} 에 알린다.
     *
     * <p>안 걸면 레지스트리에 죽은 세션이 쌓이고, 만료 대상이 실제와 어긋난다.
     */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
