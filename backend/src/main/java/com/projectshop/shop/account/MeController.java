package com.projectshop.shop.account;

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
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.Password;
import com.projectshop.shop.auth.PermissionCatalog;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.consent.ConsentService;

/**
 * 로그인한 사람이 자기에 대해 묻는 자리.
 *
 * <p>계정 조회·수정(5e)과 동의 조회·철회(5f)가 여기 붙는다.
 *
 * <p><b>이 클래스만 관객으로 묶인다.</b> 경로가 `/api/me` 라서고, 그래서 자원이 둘이다 —
 * `app_user` 는 이 패키지 것이고 동의는 {@code consent} 에서 가져온다(`5l`).
 * 패키지를 경로에 맞춰 가르지 않는다. 그러면 `app_user` 를 읽는 SQL 이 두 패키지로 흩어진다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final PermissionCatalog permissionCatalog;
    private final AccountService accountService;
    private final ConsentService consentService;
    private final WithdrawalService withdrawalService;

    public MeController(PermissionCatalog permissionCatalog, AccountService accountService,
            ConsentService consentService, WithdrawalService withdrawalService) {

        this.permissionCatalog = permissionCatalog;
        this.accountService = accountService;
        this.consentService = consentService;
        this.withdrawalService = withdrawalService;
    }

    /**
     * 탈퇴. <b>되돌릴 수 없어서 비밀번호를 다시 받는다.</b>
     *
     * <p>{@code DELETE} 를 안 쓴다. 본문을 실어야 하는데 {@code DELETE} 의 본문은 지원이 갈리고,
     * 상태를 바꾸는 요청은 무슨 일이 일어나는지를 경로에 적기로 했다(`D5`).
     */
    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody WithdrawRequest request, HttpServletRequest http) {

        withdrawalService.withdraw(user.id(), request.password(), http.getRemoteAddr());
    }

    public record WithdrawRequest(@NotBlank String password) {
    }

    /** 무엇에 동의했고 무엇을 더 켤 수 있나. <b>건드린 적 없는 항목도 나온다.</b> */
    @GetMapping("/consents")
    public List<ConsentService.ConsentView> consents(@AuthenticationPrincipal ShopUser user) {
        return consentService.list(user.id());
    }

    /**
     * 내가 동의한 판의 사본. <b>지금 효력 있는 판이 아니다</b> — 개정됐으면 둘이 다르다.
     *
     * <p>약관규제법 제3조제2항이 고객 요구 시 사본을 내주라고 하는데,
     * 그 사본은 내가 계약한 그 약관이다. 최신판을 내주면 그 사이 고친 것을 들이미는 꼴이 된다.
     *
     * <p>지금 판을 보려면 {@code /api/consent-items/{code}} 다. 그쪽은 로그인이 필요 없다.
     */
    @GetMapping("/consents/{code}")
    public ConsentService.MyNotice myConsent(
            @AuthenticationPrincipal ShopUser user, @PathVariable String code) {

        return consentService.readMine(user.id(), code);
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

    /**
     * 이름을 고친다.
     *
     * <p><b>패치 문서의 형식을 미디어 타입으로 밝힌다</b>(`Q11`). RFC 5789 는 {@code PATCH} 의
     * 본문이 「바꿀 것의 목록」이고 그 형식을 미디어 타입이 식별한다고 한다.
     * {@code application/json} 으로 부분 갱신을 보내면 <b>{@code null} 이 삭제인지 무시인지</b>를
     * 정할 자리가 없다 — 지금은 고칠 필드가 하나뿐이라 안 갈리지만, 늘면 그때 갈린다.
     *
     * <p>{@code application/merge-patch+json}(RFC 7396)이 그 답을 정해 둔 형식이다.
     * <b>{@code null} 은 그 필드를 지우라는 뜻</b>이고, 안 보낸 필드는 안 건드린다.
     */
    @PatchMapping(consumes = "application/merge-patch+json")
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

    /**
     * 새 비밀번호는 가입과 <b>같은 애너테이션</b>을 탄다(`D14`).
     *
     * <p>현재 비밀번호에는 규칙을 안 건다. 규칙이 바뀌기 전에 만든 비밀번호가 막히면
     * 그 사람은 비밀번호를 바꿀 수도 없게 된다.
     */
    public record PasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Password String newPassword) {
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
