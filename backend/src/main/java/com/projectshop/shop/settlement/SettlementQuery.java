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

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(name = "SettlementSummary")
    public record Summary(String settlementNumber, String sellerCode, LocalDate periodStart,
            LocalDate periodEnd, LocalDate payoutDate, long payoutAmount, long carriedOver,
            String payoutStatus, OffsetDateTime createdAt) {}

    /** 정산서 한 줄의 근거. {@code supplier} 는 부가가치세법이 요구하는 값이다(`D2` R17) */
    @Schema(name = "SettlementLine")
    public record Line(String kind, String supplier, long amount, Integer commissionBp,
            Long commissionBaseAmount, String sellerOrderNumber, String productName) {}

    /**
     * @param allowedActions 지금 이 정산서에 할 수 있는 것(`Q81`). <b>화면이 권한을 따로 안 묻는다</b>
     *                       (`D20` 「어디서 권한을 묻나」). 이름은 대문자고 <b>소문자·하이픈으로 바꾸면
     *                       경로</b>다 — {@code PAYOUT_REQUEST} 가 {@code /api/settlements/{번호}/payout-request} 다
     */
    @Schema(name = "SettlementDetail")
    public record Detail(Summary summary, List<Line> lines, List<String> allowedActions) {}

    @Schema(name = "SettlementPage")
    public record Page(List<Summary> items, int page, int size, long total) {}

    /**
     * 볼 수 있는 정산서 목록.
     *
     * @param everything 전체. {@code all} 스코프에서만 참이다
     * @param sellers    소속이면서 조회 권한이 열린 셀러
     */
    /**
     * 이 사람이 이 정산서에 지금 할 수 있는 것(`Q81`).
     *
     * <p><b>셋을 여기서 같이 본다</b> — 권한 · 지급 상태 · <b>자기가 올린 것인가</b>.
     * 그전에는 화면이 앞 둘만 보고 그렸고, 셋째는 <b>응답에 없어서 볼 수가 없었다</b> —
     * 그래서 자기가 올린 지급에도 승인 버튼이 뜨고 누르면 {@code 403} 이 왔다.
     * 서버는 그 사실을 이미 들고 있다({@code settlement_payout_self_approval_check}, {@code V57}).
     *
     * <p>이름은 <b>소문자·하이픈으로 바꾸면 경로</b>다. {@code PAYOUT} 은 승인이고 돈이 나간 것으로 친다.
     */
    private List<String> payoutActions(long viewerId, long sellerId, Long requestedBy,
            Summary summary) {
        Target target = Target.ofSeller(sellerId);

        // 응답의 값은 이미 대문자 이름이다(`EnumValue` 가 바꿨다). `of` 는 저장값을 받으므로 여기선 못 쓴다.
        return switch (PayoutStatus.valueOf(summary.payoutStatus())) {
            case PENDING, REJECTED -> summary.payoutAmount() > 0
                            && evaluator.decide(viewerId, RESOURCE, "request_payout", target).allowed()
                    ? List.of("PAYOUT_REQUEST")
                    : List.of();
            // 결정이 열리는 유일한 상태다(`D7`). 올린 사람은 결정을 못 한다 — 반려도 결정이라
            // {@code payout_decided_by_user_id} 를 채우고 그 제약에 걸린다.
            case REQUESTED -> !evaluator.decide(viewerId, RESOURCE, "payout", target).allowed()
                            || (requestedBy != null && requestedBy == viewerId)
                    ? List.of()
                    : List.of("PAYOUT", "PAYOUT_REJECTION");
            // 지급이 끝났다. 되돌리는 자리가 없다
            case PAID -> List.of();
        };
    }

    private record Visible(boolean everything, Long[] sellers) {}

    public Page find(long viewerId, Paging paging) {
        Visible visible = visibleFor(viewerId);

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

        // 지급 목록을 만들려면 <b>셀러</b>(권한 대상)와 <b>요청자</b>(자기 승인 금지)가 더 필요하다.
        // 응답에는 안 나간다 — 판단에만 쓰고, 나가는 것은 아래 allowedActions 한 칸이다.
        record Payout(Summary summary, long sellerId, Long requestedBy) {}

        Payout payout = jdbc.sql("""
                        select s.settlement_number, sel.code as seller_code,
                               c.period_start, c.period_end, c.payout_date,
                               s.payout_amount, s.carried_over, s.payout_status, s.created_at,
                               s.seller_id, s.payout_requested_by_user_id
                          from settlement s
                          join settlement_cycle c on c.settlement_cycle_id = s.settlement_cycle_id
                          join seller sel on sel.seller_id = s.seller_id
                         where s.settlement_number = :number
                           and (:seesEverything or s.seller_id = any(:sellers))
                        """)
                .param("number", settlementNumber)
                .param("seesEverything", visible.everything())
                .param("sellers", visible.sellers())
                .query((rs, rowNum) -> new Payout(summaryOf(rs),
                        rs.getLong("seller_id"),
                        (Long) rs.getObject("payout_requested_by_user_id")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SETTLEMENT_NOT_FOUND,
                        "그런 정산서가 없다: " + settlementNumber));

        return new Detail(payout.summary(), linesOf(settlementNumber),
                payoutActions(viewerId, payout.sellerId(), payout.requestedBy(), payout.summary()));
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
