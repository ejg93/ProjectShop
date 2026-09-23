package com.projectshop.shop.coupon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
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
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    CouponService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * 만들 쿠폰.
     *
     * <p><b>부담 주체와 셀러가 한 쌍이다</b> — 셀러 부담이면 셀러가 있고 몰 부담이면 없다.
     * 표가 {@code coupon_bearer_seller_check} 로 이미 막지만 그것은 500 이라,
     * 부르는 쪽이 고칠 수 있는 오류로 먼저 답한다.
     *
     * @param discountValue 정액이면 원, 정률이면 bp(1000 = 10.00%). 뜻은 {@code discountKind} 가 정한다
     * @param validDays     발급하면 며칠 쓸 수 있나. <b>발급 때 기한으로 박제한다</b> —
     *                      나중에 이 값을 바꿔도 이미 나간 쿠폰은 안 바뀐다(`49`)
     */
    public record NewCoupon(String code, String name, String discountKind, long discountValue,
            Long maxDiscountAmount, long minOrderAmount, String bearer, Long sellerId,
            int validDays) {}

    /**
     * 쿠폰을 만든다(`Q163`).
     *
     * <p><b>관리자뿐이다</b>(`V91`). 셀러 부담 쿠폰도 여기서 난다 — 셀러가 스스로 만들게 하려면
     * 「누가 그 부담을 승인했나」가 정산에 남아야 하고, 그 자리는 이 청크가 안 연다.
     *
     * <p><b>값 검사를 여기서 다시 안 한다.</b> 할인 종류·부호·상한·기간은 {@code coupon} 의
     * {@code check} 여섯이 이미 든다(`49`) — 요청 record 의 애노테이션이 그 앞에서
     * 400 으로 답하고, 그래도 지나간 것은 제약이 막는다.
     */
    @Transactional
    public long define(long actorUserId, NewCoupon command) {
        requirePermission(actorUserId, "coupon", "create", null);

        if ((command.sellerId() != null) != "seller".equals(command.bearer())) {
            throw new ShopException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "셀러 부담에는 셀러가 있고 몰 부담에는 없다");
        }

        long couponId;
        try {
            couponId = jdbc.sql("""
                            insert into coupon (code, name, discount_kind, discount_value,
                                                max_discount_amount, min_order_amount,
                                                bearer, seller_id, valid_days)
                            values (:code, :name, :kind, :value, :max, :min,
                                    :bearer, :seller, :validDays)
                            returning coupon_id
                            """)
                    .param("code", command.code())
                    .param("name", command.name())
                    .param("kind", command.discountKind())
                    .param("value", command.discountValue())
                    .param("max", command.maxDiscountAmount())
                    .param("min", command.minOrderAmount())
                    .param("bearer", command.bearer())
                    .param("seller", command.sellerId())
                    .param("validDays", command.validDays())
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ShopException(ErrorCode.COUPON_CODE_TAKEN);
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "coupon.defined", actorUserId,
                AuditLog.Target.of("coupon", couponId),
                Map.of("bearer", command.bearer()));

        return couponId;
    }

    /**
     * 쿠폰을 내린다 — 더 이상 발급되지 않는다(`Q163`).
     *
     * <p><b>기본 창으로 만든 쿠폰은 이것 말고 멈출 길이 없다.</b>
     * {@code coupon_issue_window_check} 가 {@code issue_end_at > issue_start_at} 을 요구해서
     * 창을 뒤로 못 당기고, {@code issue_start_at} 이 만든 시각이면 과거를 가리킬 수가 없다 —
     * 시험이 그것을 밟아서 이 입구가 생겼다.
     *
     * <p><b>이미 나간 발급은 그대로 살아 있지 않다.</b> {@code loadUsable} 과 {@link #register}
     * 가 둘 다 {@code c.deleted_at is null} 로 거르므로, 내리면 받은 사람도 못 쓴다 —
     * <b>잘못 만든 쿠폰을 멈추는 것이 이 동작의 목적</b>이라 그게 맞다.
     * 나간 것을 살려 두면서 발급만 닫으려면 {@code issue_end_at} 을 창 안에서 당긴다.
     */
    @Transactional
    public void withdraw(long actorUserId, long couponId) {
        requirePermission(actorUserId, "coupon", "delete", null);

        int down = jdbc.sql("""
                        update coupon set deleted_at = now()
                         where coupon_id = :id and deleted_at is null
                        """)
                .param("id", couponId)
                .update();
        if (down == 0) {
            throw new ShopException(ErrorCode.COUPON_CODE_NOT_ISSUABLE, "이미 내렸거나 없는 쿠폰이다");
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "coupon.withdrawn", actorUserId,
                AuditLog.Target.of("coupon", couponId), Map.of());
    }

    /**
     * 코드를 받아 자기 쿠폰함에 담는다(`Q163`, 사용자 결정 2026-09-22).
     *
     * <p><b>목록에서 고르는 것이 아니라 코드를 친다.</b> 목록을 뿌리면 아직 안 알린 쿠폰의
     * 코드가 통째로 새고, 그래서 {@code customer} 에게 {@code coupon:read} 를 안 줬다(`V91`).
     *
     * <p><b>셋을 안 가른다</b> — 없는 코드·내려간 쿠폰·발급 기간이 아닌 것이 같은 응답이다.
     * 가르면 코드를 찍어 보며 <b>어떤 쿠폰이 존재하는지</b>를 알아낼 수 있다(`D14`).
     * 이미 받은 것만 따로 답하는데, 그건 이미 자기 것이라 새로 흘리는 것이 없다.
     *
     * <p><b>기한을 발급 시점에 박제한다</b>({@code valid_days}) — 정의가 바뀌어도 이미 나간
     * 쿠폰의 기한은 안 바뀐다(`V26` 과 같은 이유).
     */
    @Transactional
    public long register(long actorUserId, String code) {
        requirePermission(actorUserId, "coupon_issue", "create", actorUserId);

        Issuable issuable = jdbc.sql("""
                        select coupon_id, valid_days from coupon
                         where code = :code
                           and deleted_at is null
                           and issue_start_at <= now()
                           and (issue_end_at is null or issue_end_at > now())
                        """)
                .param("code", code)
                .query((rs, rowNum) -> new Issuable(rs.getLong("coupon_id"), rs.getInt("valid_days")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.COUPON_CODE_NOT_ISSUABLE));

        long issueId;
        try {
            issueId = jdbc.sql("""
                            insert into coupon_issue (coupon_id, user_id, expires_at)
                            values (:coupon, :user, now() + make_interval(days => :validDays))
                            returning coupon_issue_id
                            """)
                    .param("coupon", issuable.couponId())
                    .param("user", actorUserId)
                    .param("validDays", issuable.validDays())
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // 표가 든다(`coupon_issue_once`). 앱에서만 세면 두 번 눌러 두 장이 된다.
            throw new ShopException(ErrorCode.COUPON_ALREADY_ISSUED);
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "coupon.issued", actorUserId,
                AuditLog.Target.of("coupon_issue", issueId),
                Map.of("coupon_id", issuable.couponId()));

        return issueId;
    }

    /** 지금 발급할 수 있는 정의가 아는 것 */
    private record Issuable(long couponId, int validDays) {}

    /**
     * <b>주인을 싣는다.</b> 안 실으면 {@code own} 이 아무것도 안 덮는다 —
     * `Q161` 이 상품에서, `Q160` 이 후기에서 같은 자리를 밟았다.
     *
     * <p>정의({@code coupon})에는 주인이 없어서 {@code null} 이다. 거기는 {@code all} 만
     * 덮으므로 {@code own} 을 준 역할이 지나갈 일이 없다.
     */
    private void requirePermission(long actorUserId, String resource, String action,
            Long ownerUserId) {
        Target target = new Target(ownerUserId, null, null);
        if (!evaluator.decide(actorUserId, resource, action, target).allowed()) {
            throw new ShopException(ErrorCode.ACCESS_DENIED);
        }
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

        DiscountAllocation.Result allocated = DiscountAllocation.of(
                lines.stream().map(CouponLine::amount).toList(),
                targets, base, discountOf(definition, base));

        return new Applied(couponIssueId, allocated.total(), allocated.perLine());
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
