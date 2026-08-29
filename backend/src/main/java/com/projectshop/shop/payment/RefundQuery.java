package com.projectshop.shop.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.support.EnumValue;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 환불을 찾아 본다. <b>승인자가 무엇을 승인할지 여기서 찾는다.</b>
 *
 * <p><b>이 조회가 법 요건의 강제 지점이다</b>(`D2` R5). 「기한 안에 승인」은 제약으로 못 거는데
 * (막으면 늦은 돈이 영영 안 나간다) 그 대신 <b>기한을 넘긴 것이 드러나야</b> 한다.
 * {@code refund_pending_due_idx}(`V23`)가 이 쿼리를 위해 깔린 것이다.
 *
 * <p><b>기본 정렬이 기한 임박순이다.</b> 다른 목록은 최신순인데 여기만 다르다 —
 * 이 목록을 여는 사람이 묻는 것이 "무엇이 새로 왔나" 가 아니라 "무엇이 늦고 있나" 다.
 *
 * <p><b>스코프를 {@code payment:read} 에서 읽는다.</b> 환불은 결제 기록의 뒷면이라 별도 읽기 권한을
 * 안 만들었다 — 고객 {@code own}·셀러 {@code seller}·관리자와 감사자 {@code all} 이 `V3` 에 이미 있다.
 *
 * <p><b>요청 사유를 안 내린다.</b> 소비자가 쓴 자유 텍스트라 무엇이 들어올지 모르고, 셀러에게
 * 나가는 것이 제3자 제공이다(`D2` R8). 내리려면 {@code payment} 자원에 필드 그룹이 먼저 있어야 하고
 * 지금은 없다 — 빠뜨린 것이 아니라 안 넣은 것이다(`D23`).
 */
@Service
public class RefundQuery {

    /**
     * 정렬 가능한 필드. 요청은 API 이름으로 오고 값은 실제 컬럼식이다(`D14`).
     *
     * <p>금액순을 안 연다. 이 목록으로 하는 일이 기한 관리라 금액으로 줄 세울 화면이 없다.
     */
    private static final Map<String, String> SORTABLE =
            Map.of("due_at", "r.due_at", "created_at", "r.created_at");

    /** 기한이 급한 것부터. 「무엇이 늦고 있나」가 이 목록의 물음이다(`D2` R5) */
    private static final String DEFAULT_SORT = "due_at,asc";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    RefundQuery(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 목록 한 줄.
     *
     * @param overdue 기한을 넘겼나. <b>서버가 계산해 내린다</b> — 화면이 {@code dueAt} 과 현재 시각을
     *                비교하면 시간대와 시계 차이만큼 답이 갈리고, 그 답이 법 요건이다(`D2` R5)
     */
    public record Summary(String refundNumber, String sellerOrderNumber, String orderNumber,
            String status, String reasonCode, long amount, OffsetDateTime dueAt, boolean overdue,
            OffsetDateTime createdAt) {
    }

    public record Page(List<Summary> items, int page, int size, long total) {
    }

    /** 돌려주는 항목 한 줄. 주문 시점에 박제된 이름을 그대로 쓴다 */
    public record Item(String productName, String optionLabel, int quantity, long amount) {
    }

    /**
     * 환불 하나를 펼친다.
     *
     * @param decisionReason 승인·반려의 근거. <b>반려면 반드시 있다</b>({@code refund_rejection_reason_check}) —
     *                       고객에게 왜 안 됐는지 답하는 값이라 요청 사유와 달리 내린다
     */
    public record Detail(String refundNumber, String sellerOrderNumber, String orderNumber,
            String status, String reasonCode, long amount, long shippingFeeRefund, long delayInterest,
            OffsetDateTime dueAt, boolean overdue, String gatewayRefundNumber,
            String decisionReason, OffsetDateTime decidedAt, OffsetDateTime createdAt,
            List<Item> items) {
    }

    /**
     * 볼 수 있는 환불을 훑는다.
     *
     * <p><b>스코프를 조회 조건에 섞는 자리다</b>(`permission-rules.md` 「알려진 구멍 3」).
     * 목록은 대상이 없어서 {@code decide} 를 행마다 부를 수 없고, <b>어느 행이 대상인지를 조건이
     * 정한다</b> — 조건이 곧 판정이라 틀리면 남의 환불이 섞인다.
     *
     * @param status 이 상태만. null 이면 전부. 승인 대기만 보는 것이 이 필터의 주 용도다
     */
    public Page find(long viewerId, String status, String sort, int page, int size) {
        Visible visible = visibleFor(viewerId);

        Paging paging = Paging.of(page, size);
        String orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);
        String storedStatus = storedStatus(status);

        List<Summary> items = jdbc.sql("""
                        select r.refund_number, so.seller_order_number, o.order_number,
                               r.status, r.reason_code, r.amount, r.due_at, r.created_at,
                               (r.status = 'requested' and r.due_at < now()) as overdue
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join shop_order o    on o.order_id = so.order_id
                         where (:seesEverything
                                or (:seesOwn and o.user_id = :viewerId)
                                or so.seller_id = any(:sellers))
                           and (cast(:status as text) is null or r.status = cast(:status as text))
                        """
                // 텍스트 블록이 줄 끝 공백을 지워서 "order by" 와 컬럼이 붙는다. 공백을 직접 넣는다.
                + " order by " + orderBy + ", r.refund_id desc"
                + " limit :size offset :offset")
                .param("seesEverything", visible.everything())
                .param("seesOwn", visible.own())
                .param("viewerId", viewerId)
                .param("sellers", visible.sellers())
                .param("status", storedStatus)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new Summary(
                        rs.getString("refund_number"),
                        rs.getString("seller_order_number"),
                        rs.getString("order_number"),
                        EnumValue.of(rs.getString("status"), RefundStatus::of),
                        EnumValue.of(rs.getString("reason_code"), RefundReason::of),
                        rs.getLong("amount"),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getBoolean("overdue"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("""
                        select count(*)
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join shop_order o    on o.order_id = so.order_id
                         where (:seesEverything
                                or (:seesOwn and o.user_id = :viewerId)
                                or so.seller_id = any(:sellers))
                           and (cast(:status as text) is null or r.status = cast(:status as text))
                        """)
                .param("seesEverything", visible.everything())
                .param("seesOwn", visible.own())
                .param("viewerId", viewerId)
                .param("sellers", visible.sellers())
                .param("status", storedStatus)
                .query(Long.class)
                .single();

        return new Page(items, paging.page(), paging.size(), total);
    }

    /**
     * 환불 하나를 펼친다. <b>노출 번호로 찾는다</b>(`D9`).
     *
     * <p>못 보는 것과 없는 것의 답이 같다(`D5` 「권한 실패」). 가르면 번호를 두드려서
     * 환불이 몇 건인지 셀 수 있고, 그게 곧 취소율이다.
     */
    public Detail findByNumber(long viewerId, String refundNumber) {
        Row row = jdbc.sql("""
                        select r.refund_id, r.refund_number, so.seller_order_number, o.order_number,
                               r.status, r.reason_code, r.amount, r.shipping_fee_refund, r.delay_interest,
                               r.due_at, r.gateway_refund_number, n.decision_reason,
                               r.decided_at, r.created_at,
                               (r.status = 'requested' and r.due_at < now()) as overdue,
                               o.user_id as buyer_user_id, so.seller_id
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join shop_order o    on o.order_id = so.order_id
                         left join refund_note n on n.refund_id = r.refund_id
                         where r.refund_number = :number
                        """)
                .param("number", refundNumber)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("refund_id"),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id"),
                        new Detail(
                                rs.getString("refund_number"),
                                rs.getString("seller_order_number"),
                                rs.getString("order_number"),
                                EnumValue.of(rs.getString("status"), RefundStatus::of),
                                EnumValue.of(rs.getString("reason_code"), RefundReason::of),
                                rs.getLong("amount"),
                                rs.getLong("delay_interest"),
                                rs.getLong("shipping_fee_refund"),
                                rs.getObject("due_at", OffsetDateTime.class),
                                rs.getBoolean("overdue"),
                                rs.getString("gateway_refund_number"),
                                rs.getString("decision_reason"),
                                rs.getObject("decided_at", OffsetDateTime.class),
                                rs.getObject("created_at", OffsetDateTime.class),
                                List.of())))
                .optional()
                .orElseThrow(() -> notFound(refundNumber));

        if (!evaluator.decide(viewerId, "payment", "read",
                Target.of(row.buyerUserId(), row.sellerId())).allowed()) {
            throw notFound(refundNumber);
        }

        Detail head = row.detail();
        return new Detail(head.refundNumber(), head.sellerOrderNumber(), head.orderNumber(),
                head.status(), head.reasonCode(), head.amount(), head.shippingFeeRefund(),
                head.delayInterest(), head.dueAt(), head.overdue(), head.gatewayRefundNumber(), head.decisionReason(),
                head.decidedAt(), head.createdAt(), itemsOf(row.refundId()));
    }

    /** 판정에 필요한 것까지 같이 읽은 행 */
    private record Row(long refundId, long buyerUserId, long sellerId, Detail detail) {
    }

    private List<Item> itemsOf(long refundId) {
        return jdbc.sql("""
                        select oi.product_name, oi.option_label, ri.quantity, ri.amount
                          from refund_item ri
                          join order_item oi on oi.order_item_id = ri.order_item_id
                         where ri.refund_id = :refundId
                         order by ri.refund_item_id
                        """)
                .param("refundId", refundId)
                .query((rs, rowNum) -> new Item(
                        rs.getString("product_name"),
                        rs.getString("option_label"),
                        rs.getInt("quantity"),
                        rs.getLong("amount")))
                .list();
    }

    /**
     * 이 사람이 어디까지 보나.
     *
     * @param everything 전체. {@code all} 스코프에서만 참이다
     * @param own        자기가 산 주문의 환불
     * @param sellers    소속이면서 조회 권한이 열린 셀러
     */
    private record Visible(boolean everything, boolean own, Long[] sellers) {
    }

    /**
     * 판정 결과에서 범위를 읽어 조건으로 옮긴다. <b>판정 로직을 다시 쓰지 않는다.</b>
     *
     * <p>셋 다 아니면 거부다. <b>0건이 아니다</b> — 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다
     * ({@code SellerOrderQuery} 와 같은 판단).
     */
    private Visible visibleFor(long viewerId) {
        // 남의 것 하나를 물어본다. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, "payment", "read", Target.of(-1L, -1L)).allowed()) {
            return new Visible(true, true, new Long[0]);
        }

        boolean own = evaluator.decide(viewerId, "payment", "read",
                Target.of(viewerId, -1L)).allowed();

        Set<Long> memberOf = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();

        Set<Long> sellers = memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, "payment", "read", Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());

        if (!own && sellers.isEmpty()) {
            throw new ShopException(ErrorCode.ORDER_FORBIDDEN, "환불을 볼 권한이 없다");
        }
        return new Visible(false, own, sellers.toArray(Long[]::new));
    }

    /**
     * 요청이 준 표기를 저장값으로 되돌린다.
     *
     * <p>응답은 대문자 스네이크로 나가므로(`D5`) 필터도 그 표기로 들어온다.
     * <b>모르는 값은 거부한다</b> — 조용히 무시하면 오타 하나로 전체 목록이 나가고,
     * 그 화면은 「대기 3건」 자리에 「전체 900건」을 그린다.
     */
    private static String storedStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        String stored = status.toLowerCase(Locale.ROOT);
        if (!Set.of(RefundService.REQUESTED_CODE, RefundService.APPROVED_CODE,
                RefundService.REJECTED_CODE).contains(stored)) {
            throw new ShopException(ErrorCode.VALIDATION_FAILED, "그런 환불 상태가 없다: " + status);
        }
        return stored;
    }



    private static ShopException notFound(String refundNumber) {
        return new ShopException(ErrorCode.REFUND_NOT_FOUND, "그런 환불 요청이 없다: " + refundNumber);
    }
}
