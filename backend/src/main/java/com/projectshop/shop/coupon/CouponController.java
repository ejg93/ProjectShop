package com.projectshop.shop.coupon;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ListQuery.Paging;

import org.springdoc.core.annotations.ParameterObject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 쿠폰을 만들고 받는다(`Q163`).
 *
 * <p><b>`49`·`50`·`51` 이 표·계산·정산을 세우면서 이 자리를 안 만들었다.</b> 그래서 쿠폰은
 * {@code psql} 로만 생겼고 받는 길도 없었다 — 표 셋이 아무도 못 채우는 상태였다.
 *
 * <p><b>경로가 자원을 가른다.</b> {@code /api/coupons} 는 정의고 {@code /api/me/coupons} 는
 * 내가 받은 것이다. 한 경로에 합치면 「무슨 쿠폰이 있나」와 「내가 무엇을 받았나」를
 * 화면이 응답을 보고 갈라야 한다.
 *
 * <p><b>목록을 고르는 것이 아니라 코드를 친다</b>(사용자 결정, 2026-09-22). 받을 수 있는 쿠폰을
 * 뿌리면 아직 안 알린 코드가 통째로 새서, {@code customer} 에게 {@code coupon:read} 를 안 줬다(`V91`).
 */
@RestController
public class CouponController {

    private final CouponService coupons;
    private final CouponQuery query;

    CouponController(CouponService coupons, CouponQuery query) {
        this.coupons = coupons;
        this.query = query;
    }

    /**
     * 만들 쿠폰.
     *
     * <p><b>이 record 가 {@code public} 인 근거는 대조다</b> — {@code LengthConstraintTest} 가
     * {@code @Size} 의 수를 {@code coupon_code_length_check}·{@code coupon_name_length_check} 와
     * 맞춰 보고, 그 시험이 다른 패키지에 있다(`D23` 「{@code public} 에는 근거가 있어야 한다」).
     *
     * <p><b>코드는 사람이 받아 치는 값이라 글자를 좁힌다.</b> 대문자·숫자·붙임표만 둔다 —
     * 공백이나 대소문자가 섞이면 「분명히 쳤는데 안 된다」가 오타인지 다른 쿠폰인지 모른다.
     *
     * @param discountValue 정액이면 원, 정률이면 bp(1000 = 10.00%). <b>범위는 표가 든다</b> —
     *        정률의 1~10000 은 {@code coupon_discount_value_check} 가 종류와 묶어서 잰다
     */
    public record NewCouponRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z0-9-]+") String code,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "amount|percent") String discountKind,
            @Min(1) long discountValue,
            @Min(1) Long maxDiscountAmount,
            @PositiveOrZero long minOrderAmount,
            @Pattern(regexp = "mall|seller") String bearer,
            @Min(1) Long sellerId,
            @Min(1) @Max(3650) int validDays) {}

    /** 받을 것. <b>코드 하나뿐이다</b> — 어느 쿠폰인지는 코드가 이미 가리킨다 */
    public record RegisterCouponRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z0-9-]+") String code) {}

    record CouponCreated(long couponId) {}

    record CouponIssued(long couponIssueId) {}

    /**
     * 쿠폰을 만든다. <b>201 과 {@code Location}</b>(`D5`).
     *
     * <p>가리키는 곳이 목록이다 — 정의 단건 조회가 없고, 만든 사람이 그것을 보는 자리가 거기다.
     */
    @PostMapping("/api/coupons")
    ResponseEntity<CouponCreated> define(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody NewCouponRequest request) {
        long couponId = coupons.define(user.id(), new CouponService.NewCoupon(
                request.code(), request.name(), request.discountKind(), request.discountValue(),
                request.maxDiscountAmount(), request.minOrderAmount(), request.bearer(),
                request.sellerId(), request.validDays()));

        return ResponseEntity.created(URI.create("/api/coupons")).body(new CouponCreated(couponId));
    }

    /** 살아 있는 정의 전부. 관리자만 지난다(`V91`) */
    @GetMapping("/api/coupons")
    CouponQuery.DefinitionPage list(@AuthenticationPrincipal ShopUser user,
            @ParameterObject Paging paging) {
        return query.findAll(user.id(), paging);
    }

    /**
     * 쿠폰을 내린다. <b>204 다</b>(`D5`) — 돌려줄 표현이 없다.
     *
     * <p>기본 창으로 만든 쿠폰은 이것 말고 발급을 멈출 길이 없다({@code coupon_issue_window_check}).
     */
    @DeleteMapping("/api/coupons/{couponId}")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal ShopUser user,
            @PathVariable long couponId) {
        coupons.withdraw(user.id(), couponId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * 코드를 받아 쿠폰함에 담는다. <b>201 과 {@code Location}</b> 이고 가리키는 곳이 쿠폰함이다.
     *
     * <p>같은 쿠폰을 두 번 받으면 409 다 — 표의 {@code coupon_issue_once} 가 들고,
     * 서비스가 그것을 고칠 수 있는 오류로 바꾼다.
     */
    @PostMapping("/api/me/coupons")
    ResponseEntity<CouponIssued> register(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody RegisterCouponRequest request) {
        long issueId = coupons.register(user.id(), request.code());

        return ResponseEntity.created(URI.create("/api/me/coupons"))
                .body(new CouponIssued(issueId));
    }

    /** 내 쿠폰함. <b>쓴 것과 지난 것도 같이 나간다</b> — 화면이 그 셋을 갈라 그린다 */
    @GetMapping("/api/me/coupons")
    CouponQuery.IssuedPage mine(@AuthenticationPrincipal ShopUser user,
            @ParameterObject Paging paging) {
        return query.findMine(user.id(), paging);
    }
}
