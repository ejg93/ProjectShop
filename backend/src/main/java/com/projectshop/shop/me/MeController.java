package com.projectshop.shop.me;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.PermissionCatalog;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 로그인한 사람이 자기에 대해 묻는 자리.
 *
 * <p>계정 조회·수정(5e)과 동의 조회·철회(5f)가 여기 붙는다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final PermissionCatalog permissionCatalog;
    private final AccountService accountService;
    private final ConsentService consentService;

    public MeController(PermissionCatalog permissionCatalog, AccountService accountService,
            ConsentService consentService) {

        this.permissionCatalog = permissionCatalog;
        this.accountService = accountService;
        this.consentService = consentService;
    }

    /** 무엇에 동의했고 무엇을 더 켤 수 있나. <b>건드린 적 없는 항목도 나온다.</b> */
    @GetMapping("/consents")
    public List<ConsentService.ConsentView> consents(@AuthenticationPrincipal ShopUser user) {
        return consentService.list(user.id());
    }

    /**
     * 경로에 동사를 쓴다(`D5`). 상태를 바꾸는 요청은 자원에 `PATCH` 를 쏘는 대신
     * 무슨 일이 일어나는지를 경로에 적는다.
     */
    @PostMapping("/consents/{code}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeConsent(@AuthenticationPrincipal ShopUser user,
            @PathVariable String code, HttpServletRequest http) {

        consentService.revoke(user.id(), code, http.getRemoteAddr());
    }

    @PostMapping("/consents/{code}/grant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantConsent(@AuthenticationPrincipal ShopUser user,
            @PathVariable String code, HttpServletRequest http) {

        consentService.grant(user.id(), code, http.getRemoteAddr());
    }

    /**
     * 내 계정. <b>볼 수 없는 필드는 응답에서 빠진다</b> — 무엇이 보이는지는
     * {@code _visible_field_groups} 가 알린다(`D5`).
     */
    @GetMapping
    public AccountService.Account me(@AuthenticationPrincipal ShopUser user) {
        return accountService.read(user.id());
    }

    @PatchMapping
    public AccountService.Account update(
            @AuthenticationPrincipal ShopUser user, @Valid @RequestBody UpdateRequest request) {

        return accountService.changeDisplayName(user.id(), request.displayName());
    }

    /**
     * 비밀번호 변경을 {@code PATCH} 에 안 섞는다.
     *
     * <p>현재 비밀번호를 같이 받아야 하고 응답도 계정이 아니다. 한 곳에 두면
     * 표시 이름만 바꾸는 요청에도 비밀번호 필드가 딸려 다닌다.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal ShopUser user, @Valid @RequestBody PasswordRequest request) {

        accountService.changePassword(
                user.id(), request.currentPassword(), request.newPassword());
    }

    public record UpdateRequest(@NotBlank @Size(max = 50) String displayName) {
    }

    /** 새 비밀번호 규칙은 가입과 같다(`D14`). 두 곳이 갈리면 가입은 되는데 변경이 막힌다. */
    public record PasswordRequest(
            @NotBlank String currentPassword,

            @NotBlank
            @Size(min = 8, max = 64)
            @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "ASCII 출력 가능 문자만 쓸 수 있다")
            String newPassword) {
    }

    /**
     * 지금 무엇을 할 수 있나.
     *
     * <p>화면은 이 목록으로 버튼을 보이고 감춘다. 역할 이름으로 판단하면 판정이 두 벌이 된다.
     *
     * <p><b>접근 허용에 쓰면 안 된다.</b> 목록은 대상 행이 없는 근사치고,
     * 실제 허용은 자원을 만질 때 판정이 다시 정한다.
     */
    @GetMapping("/permissions")
    public PermissionsResponse permissions(@AuthenticationPrincipal ShopUser user) {
        return new PermissionsResponse(user.id(), permissionCatalog.listFor(user.id()));
    }

    public record PermissionsResponse(long userId, List<PermissionCatalog.Entry> permissions) {
    }
}
