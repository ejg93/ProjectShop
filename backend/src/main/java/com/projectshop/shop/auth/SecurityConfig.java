package com.projectshop.shop.auth;

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
            "/actuator/health/**");

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

        // CSRF 는 켜 둔 채로 둔다.
        //
        // 청크 5b 가 "CSRF 를 켠다" 로 잡혀 있지만 껐다가 되살리는 순서로 가지 않는다.
        // 끄면 그 사이에 생기는 POST 엔드포인트들이 토큰 없이 동작하는 것을 전제로 짜이고,
        // 나중에 켜는 순간 전부 403 이 된다. 5b 에 남는 것은 프론트가 토큰을 실어 보내는 쪽이다.

        return http.build();
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
