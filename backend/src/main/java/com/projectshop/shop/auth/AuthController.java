package com.projectshop.shop.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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
    private final CartService cartService;
    private final LoginAttemptService loginAttemptService;

    /** 직접 만들지 않고 받는다. 저장 방식을 정하는 곳은 {@code SecurityConfig} 하나여야 한다. */
    private final SecurityContextRepository securityContextRepository;

    public AuthController(SignupService signupService,
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, CartService cartService,
            LoginAttemptService loginAttemptService) {

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
