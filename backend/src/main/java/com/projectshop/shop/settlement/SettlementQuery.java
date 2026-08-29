package com.projectshop.shop.settlement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.support.EnumValue;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 정산서를 읽는다(청크 20).
 *
 * <h2>판정을 다시 쓰지 않는다</h2>
 *
 * <p>범위를 <b>판정 엔진에게 물어서</b> 조건으로 옮긴다({@code RefundQuery.visibleFor} 와 같은 모양).
 * 쿼리에 {@code seller_id} 를 손으로 끼우면 그 자리마다 새는 자리가 생기고,
 * 스코프 규칙이 바뀔 때 고칠 곳이 판정 엔진 밖으로 흩어진다.
 *
 * <h2>못 봄이 0건이 아니다</h2>
 *
 * <p>권한이 하나도 없으면 빈 목록이 아니라 거부다. <b>0건과 못 봄이 갈려야 개수로 정보가
 * 안 샌다</b> — 정산 건수는 곧 그 셀러가 거래한 달 수다.
 *
 * <h2>줄을 상세에서만 내린다</h2>
 *
 * <p>목록은 정산서당 한 줄이다. 항목은 주문 항목 건별이라(청크 17) 목록에 실으면
 * 한 셀러의 한 달치가 수백 줄이 되고, 그것을 세 페이지 긁으면 <b>거래 내역 전체</b>가 된다.
 */
@Component
public class SettlementQuery {

    private static final String RESOURCE = "settlement";
    private static final String READ = "read";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    SettlementQuery(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 목록의 한 줄.
     *
     * @param carriedOver 다음 주기로 넘기는 음수 잔액. 안 넘기면 0 이다
     */
    public record Summary(String settlementNumber, String sellerCode, LocalDate periodStart,
            LocalDate periodEnd, LocalDate payoutDate, long payoutAmount, long carriedOver,
            String payoutStatus, OffsetDateTime createdAt) {}

    /** 정산서 한 줄의 근거. {@code supplier} 는 부가가치세법이 요구하는 값이다(`D2` R17) */
    public record Line(String kind, String supplier, long amount, Integer commissionBp,
            Long commissionBaseAmount, String sellerOrderNumber, String productName) {}

    public record Detail(Summary summary, List<Line> lines) {}

    public record Page(List<Summary> items, int page, int size, long total) {}

    /**
     * 볼 수 있는 정산서 목록.
     *
     * @param everything 전체. {@code all} 스코프에서만 참이다
     * @param sellers    소속이면서 조회 권한이 열린 셀러
     */
    private record Visible(boolean everything, Long[] sellers) {}

    public Page find(long viewerId, int page, int size) {
        Visible visible = visibleFor(viewerId);
        Paging paging = Paging.of(page, size);

        List<Summary> items = jdbc.sql("""
                        select s.settlement_number, sel.code as seller_code,
                               c.period_start, c.period_end, c.payout_date,
                               s.payout_amount, s.carried_over, s.payout_status, s.created_at
                          from settlement s
                          join settlement_cycle c on c.settlement_cycle_id = s.settlement_cycle_id
                          join seller sel on sel.seller_id = s.seller_id
                         where :seesEverything or s.seller_id = any(:sellers)
                         order by c.period_start desc, sel.code, s.settlement_id desc
                         limit :size offset :offset
                        """)
                .param("seesEverything", visible.everything())
                .param("sellers", visible.sellers())
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> summaryOf(rs))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from settlement s
                         where :seesEverything or s.seller_id = any(:sellers)
                        """)
                .param("seesEverything", visible.everything())
                .param("sellers", visible.sellers())
                .query(Long.class)
                .single();

        return new Page(items, paging.page(), paging.size(), total);
    }

    /**
     * 정산서 하나와 그 줄 전부.
     *
     * <p><b>못 보는 것도 404 다.</b> 403 을 주면 번호를 훑어서 실재하는 정산서를 셀 수 있고,
     * 그것이 곧 셀러 수 × 개월이다(`D5` 의 자원별 표, {@code RefundQuery} 와 같은 판단).
     */
    public Detail findOne(long viewerId, String settlementNumber) {
        Visible visible = visibleFor(viewerId);

        Summary summary = jdbc.sql("""
                        select s.settlement_number, sel.code as seller_code,
                               c.period_start, c.period_end, c.payout_date,
                               s.payout_amount, s.carried_over, s.payout_status, s.created_at
                          from settlement s
                          join settlement_cycle c on c.settlement_cycle_id = s.settlement_cycle_id
                          join seller sel on sel.seller_id = s.seller_id
                         where s.settlement_number = :number
                           and (:seesEverything or s.seller_id = any(:sellers))
                        """)
                .param("number", settlementNumber)
                .param("seesEverything", visible.everything())
                .param("sellers", visible.sellers())
                .query((rs, rowNum) -> summaryOf(rs))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SETTLEMENT_NOT_FOUND,
                        "그런 정산서가 없다: " + settlementNumber));

        return new Detail(summary, linesOf(settlementNumber));
    }

    /**
     * 줄을 종류·번호 순으로 내린다.
     *
     * <p>배송비 줄은 상품이 없고, 이월 줄은 주문도 상품도 없다 — 그 칸이 비는 것이
     * 종류에서 이미 정해져 있다(`V52` 의 {@code settlement_item_source_check}).
     */
    private List<Line> linesOf(String settlementNumber) {
        return jdbc.sql("""
                        select i.kind, i.supplier, i.amount,
                               i.commission_bp, i.commission_base_amount,
                               coalesce(so.seller_order_number, rso.seller_order_number,
                                        sso.seller_order_number) as seller_order_number,
                               coalesce(oi.product_name, roi.product_name) as product_name
                          from settlement_item i
                          join settlement s on s.settlement_id = i.settlement_id
                          left join order_item oi on oi.order_item_id = i.order_item_id
                          left join seller_order so on so.seller_order_id = oi.seller_order_id
                          left join refund_item ri on ri.refund_item_id = i.refund_item_id
                          left join order_item roi on roi.order_item_id = ri.order_item_id
                          left join seller_order rso on rso.seller_order_id = roi.seller_order_id
                          left join seller_order sso on sso.seller_order_id = i.seller_order_id
                         where s.settlement_number = :number
                         order by i.kind, i.settlement_item_id
                        """)
                .param("number", settlementNumber)
                .query((rs, rowNum) -> new Line(
                        EnumValue.of(rs.getString("kind"), SettlementItemKind::of),
                        EnumValue.of(rs.getString("supplier"), SettlementSupplier::of),
                        rs.getLong("amount"),
                        (Integer) rs.getObject("commission_bp"),
                        (Long) rs.getObject("commission_base_amount"),
                        rs.getString("seller_order_number"),
                        rs.getString("product_name")))
                .list();
    }

    /** 판정 결과에서 범위를 읽어 조건으로 옮긴다. <b>판정 로직을 다시 쓰지 않는다.</b> */
    private Visible visibleFor(long viewerId) {
        // 남의 것 하나를 물어본다. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, RESOURCE, READ, Target.ofSeller(-1L)).allowed()) {
            return new Visible(true, new Long[0]);
        }

        Set<Long> memberOf = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();

        Set<Long> sellers = memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, RESOURCE, READ, Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());

        if (sellers.isEmpty()) {
            throw new ShopException(ErrorCode.SETTLEMENT_FORBIDDEN, "정산서를 볼 권한이 없다");
        }
        return new Visible(false, sellers.toArray(Long[]::new));
    }

    private static Summary summaryOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Summary(
                rs.getString("settlement_number"),
                rs.getString("seller_code"),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getObject("payout_date", LocalDate.class),
                rs.getLong("payout_amount"),
                rs.getLong("carried_over"),
                EnumValue.of(rs.getString("payout_status"), PayoutStatus::of),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
