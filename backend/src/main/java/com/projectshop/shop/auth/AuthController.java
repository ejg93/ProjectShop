package com.projectshop.shop.auth;

import java.time.LocalDate;
import java.util.Map;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.cart.CartController;
import com.projectshop.shop.cart.CartService;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
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
    private final CartService cartService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordResetService passwordResetService;

    /**
     * 재설정 링크의 틀. `{token}` 자리에 토큰이 들어간다.
     *
     * <p><b>화면 주소라 저장소가 아니라 환경이 든다</b> — 로컬은 3000, 배포는 공개 도메인이다.
     */
    private final String resetUrlTemplate;

    /** 직접 만들지 않고 받는다. 저장 방식을 정하는 곳은 {@code SecurityConfig} 하나여야 한다. */
    private final SecurityContextRepository securityContextRepository;

    public AuthController(SignupService signupService,
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, CartService cartService,
            LoginAttemptService loginAttemptService, PasswordResetService passwordResetService,
            @Value("${app.password-reset.url-template:http://localhost:3000/password-reset?token={token}}")
            String resetUrlTemplate) {

        this.passwordResetService = passwordResetService;
        this.resetUrlTemplate = resetUrlTemplate;
        this.signupService = signupService;
        this.cartService = cartService;
        this.loginAttemptService = loginAttemptService;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signUp(
            @Valid @RequestBody SignupRequest request, HttpServletRequest http) {

        long userId = signupService.signUp(new SignupService.Command(
                request.email(),
                request.password(),
                request.displayName(),
                request.birthDate(),
                request.consents(),
                http.getRemoteAddr()));

        // 새 자원이 선 주소를 가리킨다(`D5` 「헤더」, `Q166`). 조회는 `16` 이 만들었고,
        // **본인은 역할 권한 없이 읽는다** — 갓 가입한 사람은 아직 아무 역할도 없다.
        return ResponseEntity.created(URI.create("/api/users/" + userId))
                .body(new SignupResponse(userId));
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

        String ip = http.getRemoteAddr();

        // 차단 중에도 같은 문구다(D14). 문구가 갈리면 "이 계정은 잠겼다" 가 새어 나가고,
        // 그건 곧 그 계정이 존재한다는 뜻이다.
        //
        // 비밀번호를 대조하기 전에 본다. 뒤에 두면 차단된 상태에서도 해시 계산이 돌아서
        // 응답 시간이 갈리고, 그 차이가 계정 존재 여부를 흘린다.
        if (loginAttemptService.isBlocked(request.email(), ip)) {
            throw new ShopException(ErrorCode.LOGIN_FAILED);
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.email(), request.password()));
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(request.email(), ip);

            // 없는 계정도, 틀린 비밀번호도, 정지된 계정도 같은 문구로 나간다(D14).
            // 문구가 갈리면 가입 여부를 물어보는 도구가 된다.
            throw new ShopException(ErrorCode.LOGIN_FAILED);
        }

        loginAttemptService.reset(request.email(), ip);

        // 세션 ID 재발급과 레지스트리 등록. 이 줄이 빠지면 세션 고정 방어가 사라진다.
        sessionAuthenticationStrategy.onAuthentication(authentication, http, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, http, response);

        ShopUserDetailsService.ShopUser user =
                (ShopUserDetailsService.ShopUser) authentication.getPrincipal();

        // 비로그인으로 담아 둔 것을 계정으로 옮긴다. 옮긴 뒤 그 장바구니는 사라진다 —
        // 남겨 두면 같은 물건이 두 군데 있고 다음 로그인에 또 병합된다.
        //
        // 여기서 부르는 이유는 이 시점이 두 주인이 동시에 보이는 유일한 자리라서다.
        // 이벤트로 미루면 D12 가 필요한데 그건 아직 없다.
        cartService.mergeIntoAccount(user.id(), CartController.tokenOf(http));
        expireCartCookie(response);

        return new LoginResponse(user.id(), user.email());
    }

    /**
     * 비밀번호 재설정을 요청한다(`5c-1`).
     *
     * <p><b>가입 여부와 무관하게 202 다.</b> 없는 주소면 아무것도 안 보내고 같은 응답으로
     * 돌아간다 — 갈리면 이 입구가 <b>가입 여부를 물어보는 도구</b>가 된다(`D14`
     * 「응답 문구는 계정 존재 여부를 안 흘린다」).
     *
     * <p><b>200 이 아니라 202 인 것은 사실에 맞춰서다.</b> 우리가 한 것은 접수지 발송 완료가
     * 아니다 — 메일이 실제로 닿았는지는 이 응답이 모른다.
     */
    @PostMapping("/password-reset")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email(), resetUrlTemplate);
        return ResponseEntity.accepted().build();
    }

    /**
     * 토큰으로 비밀번호를 다시 정한다(`5c-1`).
     *
     * <p>현재 비밀번호를 안 묻는다 — 그것을 아는 사람은 `/api/me/password` 를 쓴다.
     * 여기서 본인 확인은 <b>메일로만 간 토큰을 가졌다는 것</b>이다.
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
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

    /**
     * 병합이 끝났으니 쿠키를 거둔다.
     *
     * <p>안 거두면 로그아웃한 뒤 그 토큰으로 다시 빈 장바구니가 만들어지고,
     * 사용자는 방금 옮긴 것이 사라진 것처럼 본다.
     */
    private static void expireCartCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(CartController.CART_COOKIE, "")
                .path("/")
                .maxAge(0)
                .build()
                .toString());
    }

    /** 재설정 요청. <b>주소만 받는다</b> — 이 입구는 아무나 부른다 */
    public record PasswordResetRequest(@NotBlank @EmailAddress String email) {
    }

    /** 재설정 확정. 토큰과 새 비밀번호다 */
    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Password String newPassword) {
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
            @NotBlank @EmailAddress String email,

            @NotBlank @Password String password,

            @NotBlank @Size(max = 50) String displayName,

            /* 만 19세 이상인지는 서비스가 본다(`11b`) — 나이는 오늘(KST)에 걸려서 애노테이션이 못 잰다 */
            @NotNull @Past LocalDate birthDate,

            @NotNull Map<String, Boolean> consents) {
    }

    public record SignupResponse(long userId) {
    }
}
