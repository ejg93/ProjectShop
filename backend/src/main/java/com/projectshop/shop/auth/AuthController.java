package com.projectshop.shop.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 계정을 만드는 입구. 로그인·로그아웃은 청크 5 가 여기에 붙인다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignupService signupService;

    public AuthController(SignupService signupService) {
        this.signupService = signupService;
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
     * 비밀번호는 길이와 문자 집합만 본다(D14). 조합을 강제하면 예측 가능한 변형이 나온다.
     *
     * @param consents 항목 코드 → 동의 여부. 필수 항목은 전부 true 여야 한다.
     */
    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,

            // ASCII 출력 가능 문자만 받는다. 이 제한이 bcrypt 의 72바이트 절단 구간을 없앤다 —
            // 한글은 글자당 3바이트라 24자에서 닿지만, ASCII 는 64자가 64바이트다.
            @NotBlank
            @Size(min = 8, max = 64)
            @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "ASCII 출력 가능 문자만 쓸 수 있다")
            String password,

            @NotBlank @Size(max = 50) String displayName,

            @NotNull Map<String, Boolean> consents) {
    }

    public record SignupResponse(long userId) {
    }
}
