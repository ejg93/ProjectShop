package com.projectshop.shop.coupon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 쿠폰을 주문에 적용한다 — 얼마를 깎고 그 값을 항목에 어떻게 나누나(`50`).
 *
 * <h2>계산 순서를 한 곳에서만 정한다</h2>
 *
 * <p>할인은 <b>부가세를 포함한 판매가</b>에 걸린다. 우리 금액은 전부 세포함이고
 * (`sku.price_incl_vat`·`order_item.unit_price_incl_vat`), 역산은 표시할 때만 한다(`D8`).
 *
 * <h2>배분해서 항목에 박는다</h2>
 *
 * <p>주문 하나에 한 장이지만 할인액은 <b>항목마다 나뉜다</b> — 정산이 셀러별로 갈라야 하고(`51`),
 * 부분 취소·환불이 항목 단위다. 주문 머리에만 두면 그 둘이 매번 배분을 다시 하고,
 * <b>하는 곳마다 절사가 갈린다.</b>
 *
 * <h2>절사는 내림이고 잔차는 마지막이 먹는다</h2>
 *
 * <p>비례 배분은 나누어떨어지지 않는다. 항목마다 내리면 <b>합이 총 할인액보다 작아지고</b>,
 * 그 차이가 결제액 등식을 깨뜨린다({@code assert_order_amounts}). 마지막 대상 항목이
 * 잔차를 흡수해서 <b>합이 정확히 맞는다</b> — 어느 항목이 먹든 소비자가 내는 값은 같고,
 * 정산에서 1원이 어느 셀러로 가느냐만 달라진다(`D8` 「절사 단위」).
 */
@Service
public class CouponService {

    private final JdbcClient jdbc;

    CouponService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 배분 대상 한 줄. 쿠폰은 주문의 구조를 몰라도 되고 셀러와 금액만 있으면 된다.
     *
     * <p><b>이름이 {@code Line} 이 아니다.</b> springdoc 이 짧은 이름으로 스키마를 지어서
     * {@code RefundService.Line} 과 겹치고, 겹치면 스펙에서 한쪽이 다른 쪽을 덮어쓴다(`Q45`).
     */
    public record CouponLine(long sellerId, long amount) {}

    /**
     * 적용 결과.
     *
     * @param couponIssueId 쓴 발급. 주문이 서면 {@link #markUsed} 가 이 값으로 잇는다
     * @param perLine       {@code lines} 와 같은 순서·같은 길이의 배분액
     */
    public record Applied(long couponIssueId, long total, List<Long> perLine) {}

    /**
     * 쓸 수 있는지 보고 배분한다.
     *
     * <p><b>여기서 표를 안 고친다.</b> 주문이 실제로 서야 쿠폰이 쓰인 것이라,
     * 쓴 표시는 주문 번호가 나온 뒤 {@link #markUsed} 가 한다.
     *
     * @throws ShopException 쓸 수 없는 쿠폰이거나 조건을 안 채웠을 때
     */
    public Applied apply(long userId, long couponIssueId, List<CouponLine> lines) {
        Definition definition = loadUsable(userId, couponIssueId);

        // 셀러 부담이면 그 셀러의 줄만 대상이다. 몰 부담이면 전부다.
        List<Integer> targets = new ArrayList<>();
        long base = 0;
        for (int i = 0; i < lines.size(); i++) {
            CouponLine line = lines.get(i);
            if (definition.sellerId() == null || definition.sellerId() == line.sellerId()) {
                targets.add(i);
                base += line.amount();
            }
        }

        if (targets.isEmpty()) {
            throw new ShopException(ErrorCode.COUPON_NOT_APPLICABLE, "이 주문에 쓸 수 없는 쿠폰이다");
        }
        if (base < definition.minOrderAmount()) {
            throw new ShopException(ErrorCode.COUPON_NOT_APPLICABLE, "최소 주문 금액에 못 미친다");
        }

        long total = Math.min(discountOf(definition, base), base);

        List<Long> perLine = new ArrayList<>(java.util.Collections.nCopies(lines.size(), 0L));
        long assigned = 0;
        for (int index = 0; index < targets.size() - 1; index++) {
            int lineIndex = targets.get(index);
            // 내림이다. 항목값 비율로 나누고 원 미만은 버린다.
            long share = total * lines.get(lineIndex).amount() / base;
            perLine.set(lineIndex, share);
            assigned += share;
        }
        // 마지막이 잔차를 먹는다. 이 한 줄이 합을 정확히 맞춘다.
        perLine.set(targets.get(targets.size() - 1), total - assigned);

        return new Applied(couponIssueId, total, perLine);
    }

    /**
     * 쓴 표시를 한다. <b>조건이 붙은 갱신이라 두 요청이 겹쳐도 한 번만 통과한다</b> —
     * 그것을 막는 것은 유니크가 아니라 이 갱신의 {@code where} 다.
     */
    public void markUsed(long couponIssueId, long orderId) {
        int used = jdbc.sql("""
                        update coupon_issue
                           set used_at = now(), used_order_id = :orderId
                         where coupon_issue_id = :id and used_at is null
                        """)
                .param("id", couponIssueId)
                .param("orderId", orderId)
                .update();
        if (used == 0) {
            throw new ShopException(ErrorCode.COUPON_NOT_USABLE, "이미 쓴 쿠폰이다");
        }
    }

    /** 정률은 bp 다(1000 = 10.00%). 상한이 있으면 거기서 자른다 */
    private long discountOf(Definition definition, long base) {
        if ("amount".equals(definition.discountKind())) {
            return definition.discountValue();
        }
        long raw = base * definition.discountValue() / 10000;
        return definition.maxDiscountAmount() == null
                ? raw
                : Math.min(raw, definition.maxDiscountAmount());
    }

    /**
     * 쓸 수 있는 발급 하나.
     *
     * <p><b>넷을 안 가른다</b> — 없는 발급·남의 발급·이미 쓴 것·기한이 지난 것이 같은 응답이다.
     * 가르면 번호를 두드려 <b>남이 무슨 쿠폰을 받았는지</b>를 알아낼 수 있다(`D14`).
     */
    private Definition loadUsable(long userId, long couponIssueId) {
        return jdbc.sql("""
                        select c.discount_kind, c.discount_value, c.max_discount_amount,
                               c.min_order_amount, c.seller_id
                          from coupon_issue ci
                          join coupon c on c.coupon_id = ci.coupon_id
                         where ci.coupon_issue_id = :id
                           and ci.user_id = :userId
                           and ci.used_at is null
                           and ci.expires_at > now()
                           and c.deleted_at is null
                        """)
                .param("id", couponIssueId)
                .param("userId", userId)
                .query((rs, rowNum) -> new Definition(
                        rs.getString("discount_kind"),
                        rs.getLong("discount_value"),
                        (Long) rs.getObject("max_discount_amount"),
                        rs.getLong("min_order_amount"),
                        (Long) rs.getObject("seller_id")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.COUPON_NOT_USABLE, "쓸 수 없는 쿠폰이다"));
    }

    private record Definition(String discountKind, long discountValue, Long maxDiscountAmount,
            long minOrderAmount, Long sellerId) {

        Definition {
            Objects.requireNonNull(discountKind);
        }
    }
}
