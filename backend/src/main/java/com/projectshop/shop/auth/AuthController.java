package com.projectshop.shop.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 계정을 만드는 입구. 로그인·로그아웃은 청크 5 가 여기에 붙인다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignupService signupService;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(SignupService signupService,
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {

        this.signupService = signupService;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signUp(
            @Valid @RequestBody SignupRequest request, HttpServletRequest http) {

        long userId = signupService.signUp(new SignupService.Command(
                request.email(),
                request.password(),
                request.displayName(),
                request.consents(),
                http.getRemoteAddr()));

        // Location 헤더를 안 붙인다. D5 는 201 에 새 자원 경로를 요구하지만
        // 계정 조회 API 가 아직 없다. 없는 경로를 가리키는 헤더는 클라이언트를 속인다.
        // 조회가 생기는 청크에서 붙인다.
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(userId));
    }

    /**
     * 아이디와 비밀번호를 대조하고 세션을 연다.
     *
     * <p>{@code formLogin} 을 껐으므로 이 흐름을 대신 해 주는 것이 없다.
     * 인증, 세션 처리, 컨텍스트 저장을 <b>전부 직접</b> 부른다 — 하나라도 빠뜨리면
     * 로그인은 성공하는데 다음 요청에서 인증이 안 남아 있다.
     */
    @PostMapping("/login")
    public LoginResponse logIn(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http, HttpServletResponse response) {

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.email(), request.password()));
        } catch (AuthenticationException e) {
            // 없는 계정도, 틀린 비밀번호도, 정지된 계정도 같은 문구로 나간다(D14).
            // 문구가 갈리면 가입 여부를 물어보는 도구가 된다.
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않는다");
        }

        // 세션 ID 재발급과 레지스트리 등록. 이 줄이 빠지면 세션 고정 방어가 사라진다.
        sessionAuthenticationStrategy.onAuthentication(authentication, http, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, http, response);

        ShopUserDetailsService.ShopUser user =
                (ShopUserDetailsService.ShopUser) authentication.getPrincipal();
        return new LoginResponse(user.id(), user.email());
    }

    /**
     * 세션을 버린다.
     *
     * <p>무엇을 할 수 있는지는 안 내려준다. 그건 청크 8a 의 몫이다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logOut(HttpServletRequest http, HttpServletResponse response) {
        new SecurityContextLogoutHandler()
                .logout(http, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(long userId, String email) {
    }

    /**
     * 비밀번호 규칙은 {@link Password} 하나가 들고 있다(`D14`).
     *
     * @param consents 항목 코드 → 동의 여부. 필수 항목은 전부 true 여야 한다.
     */
    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,

            @NotBlank @Password String password,

            @NotBlank @Size(max = 50) String displayName,

            @NotNull Map<String, Boolean> consents) {
    }

    public record SignupResponse(long userId) {
    }
}
