package com.projectshop.shop.coupon;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 쿠폰을 읽는다(`Q163`).
 *
 * <p><b>두 자원이 두 목록이다.</b> 정의({@code coupon})는 관리자가 보고 발급({@code coupon_issue})은
 * 본인이 본다 — 합치면 「무슨 쿠폰이 있나」와 「내가 무엇을 받았나」가 같은 응답이 되고,
 * 그때 <b>아직 안 알린 쿠폰의 코드가 통째로 샌다</b>(`D14`).
 *
 * <p><b>경계를 거는 자리가 둘로 갈린다.</b> 내 쿠폰함은 {@code user_id} 가 곧 경계라
 * SQL 이 좁히고 — 그 값을 안 받으면 질의가 서지 않는다 — 정의 목록은 좁힐 값이 없어서
 * <b>판정을 지난다.</b> 안 걸면 로그인한 누구나 아직 안 알린 쿠폰의 코드를 통째로 읽는다.
 */
@Service
public class CouponQuery {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    CouponQuery(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 내 쿠폰함 한 줄.
     *
     * <p><b>코드가 없다.</b> 받고 나면 코드로 할 일이 없고, 실으면 <b>받은 사람이 그것을
     * 퍼뜨리는 경로</b>가 된다 — 쿠폰이 사람에 붙어 있는 설계와 어긋난다(`49`).
     *
     * @param discountValue 정액이면 원, 정률이면 bp. 뜻은 {@code discountKind} 가 정한다
     * @param usedAt        썼으면 그 시각. 안 썼으면 {@code null} 이다
     */
    @Schema(name = "MyCoupon")
    public record Issued(long couponIssueId, String name, String discountKind, long discountValue,
            Long maxDiscountAmount, long minOrderAmount, OffsetDateTime expiresAt,
            OffsetDateTime usedAt) {}

    /** 관리자가 보는 정의 한 줄. <b>여기에는 코드가 있다</b> — 그것을 알려 주는 것이 이 목록의 일이다 */
    @Schema(name = "CouponDefinition")
    public record Definition(long couponId, String code, String name, String discountKind,
            long discountValue, Long maxDiscountAmount, long minOrderAmount, String bearer,
            Long sellerId, int validDays, OffsetDateTime issueStartAt,
            OffsetDateTime issueEndAt) {}

    @Schema(name = "MyCouponPage")
    public record IssuedPage(List<Issued> items, int page, int size, long total) {}

    @Schema(name = "CouponDefinitionPage")
    public record DefinitionPage(List<Definition> items, int page, int size, long total) {}

    /**
     * 내 쿠폰함.
     *
     * <p><b>쓴 것과 지난 것도 같이 준다.</b> 안 보여 주면 「분명히 받았는데 없다」에 답할 자리가
     * 없고, 화면이 그 셋을 갈라 그린다(`D20`).
     */
    public IssuedPage findMine(long userId, Paging paging) {
        List<Issued> items = jdbc.sql("""
                        select ci.coupon_issue_id, c.name, c.discount_kind, c.discount_value,
                               c.max_discount_amount, c.min_order_amount,
                               ci.expires_at, ci.used_at
                          from coupon_issue ci
                          join coupon c on c.coupon_id = ci.coupon_id
                         where ci.user_id = :userId
                         order by ci.used_at nulls first, ci.expires_at, ci.coupon_issue_id
                         limit :size offset :offset
                        """)
                .param("userId", userId)
                .param("size", paging.size())
                .param("offset", paging.page() * paging.size())
                .query((rs, rowNum) -> new Issued(
                        rs.getLong("coupon_issue_id"),
                        rs.getString("name"),
                        rs.getString("discount_kind"),
                        rs.getLong("discount_value"),
                        (Long) rs.getObject("max_discount_amount"),
                        rs.getLong("min_order_amount"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("used_at", OffsetDateTime.class)))
                .list();

        long total = jdbc.sql("select count(*) from coupon_issue where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();

        return new IssuedPage(items, paging.page(), paging.size(), total);
    }

    /**
     * 살아 있는 정의 전부. 내린 것({@code deleted_at})은 안 든다.
     *
     * <p><b>{@code coupon:read} 를 지난다.</b> 이 목록에는 코드가 실려 있고,
     * 코드를 아는 것이 곧 그 쿠폰을 받을 수 있다는 뜻이다(`Q163`) — 조회가 사실상 발급이라
     * 읽기 권한을 조회에 그대로 건다. 대상에 주인이 없어서 {@code all} 만 덮는다.
     */
    public DefinitionPage findAll(long actorUserId, Paging paging) {
        if (!evaluator.decide(actorUserId, "coupon", "read", new Target(null, null, null))
                .allowed()) {
            throw new ShopException(ErrorCode.ACCESS_DENIED);
        }

        List<Definition> items = jdbc.sql("""
                        select coupon_id, code, name, discount_kind, discount_value,
                               max_discount_amount, min_order_amount, bearer, seller_id,
                               valid_days, issue_start_at, issue_end_at
                          from coupon
                         where deleted_at is null
                         order by coupon_id desc
                         limit :size offset :offset
                        """)
                .param("size", paging.size())
                .param("offset", paging.page() * paging.size())
                .query((rs, rowNum) -> new Definition(
                        rs.getLong("coupon_id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("discount_kind"),
                        rs.getLong("discount_value"),
                        (Long) rs.getObject("max_discount_amount"),
                        rs.getLong("min_order_amount"),
                        rs.getString("bearer"),
                        (Long) rs.getObject("seller_id"),
                        rs.getInt("valid_days"),
                        rs.getObject("issue_start_at", OffsetDateTime.class),
                        rs.getObject("issue_end_at", OffsetDateTime.class)))
                .list();

        long total = jdbc.sql("select count(*) from coupon where deleted_at is null")
                .query(Long.class)
                .single();

        return new DefinitionPage(items, paging.page(), paging.size(), total);
    }
}
