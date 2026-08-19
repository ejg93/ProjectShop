package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 셀러가 자기에게 넘어온 주문을 본다.
 *
 * <p><b>{@code seller_order_visible} 뷰만 읽는다.</b> 결제가 끝난 것만 드는 뷰라
 * 미결제 조건을 여기 다시 적지 않는다 — 적으면 그 조건이 두 군데가 되고,
 * 새 조회에서 빠뜨렸을 때 <b>미결제 건이 셀러에게 새면서 오류는 안 난다.</b>
 *
 * <p>구매자가 보는 경로는 {@link OrderQuery} 다. 조건의 성격이 달라서 갈라 뒀다.
 */
@Service
public class SellerOrderQuery {

    private static final Map<String, String> SORTABLE = Map.of("created_at", "so.created_at");

    private static final String DEFAULT_SORT = "created_at,desc";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final OrderActionService actions;

    SellerOrderQuery(JdbcClient jdbc, PermissionEvaluator evaluator, OrderActionService actions) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.actions = actions;
    }

    /** 처리 화면의 한 줄. 무엇을 보낼지는 상세가 답한다 */
    public record Summary(String sellerOrderNumber, String orderNumber, String status,
            int itemCount, long shippingFee, OffsetDateTime createdAt) {
    }

    public record Page(List<Summary> items, int page, int size, long total) {
    }

    /**
     * 셀러가 보는 묶음 하나.
     *
     * @param allowedActions 지금 이 묶음에 할 수 있는 것. <b>밑줄이 없다</b> —
     *                       `D5` 의 밑줄은 "이 응답이 깎였다" 는 표시고 이건 그게 아니다
     * @param shipping       받는 사람. {@code shipping} 그룹이라 못 보면 응답에서 빠진다(`D5`)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String sellerOrderNumber, String orderNumber, String status,
            long shippingFee, OffsetDateTime deliveredAt, OffsetDateTime withdrawalExpireAt,
            OffsetDateTime autoConfirmAt, OffsetDateTime createdAt,
            OffsetDateTime shipDueAt, OffsetDateTime shippedAt, boolean shipOverdue,
            List<OrderQuery.Item> items, List<String> allowedActions, OrderQuery.Shipping shipping,
            @JsonProperty("_visible_field_groups") List<String> visibleFieldGroups) {
    }

    /** 판정에 필요한 것까지 같이 읽은 행. 주인과 셀러가 있어야 대상을 만든다 */
    private record Row(long sellerOrderId, long orderId, String sellerOrderNumber,
            String orderNumber, long buyerUserId, long sellerId, String status, long shippingFee,
            OffsetDateTime deliveredAt, OffsetDateTime withdrawalExpireAt,
            OffsetDateTime autoConfirmAt, OffsetDateTime createdAt,
            OffsetDateTime shipDueAt, OffsetDateTime shippedAt, boolean shipOverdue) {
    }

    /**
     * 내 셀러로 넘어온 것을 훑는다.
     *
     * <p><b>스코프를 조회 조건에 섞는 자리다</b>(「알려진 구멍 3」). 목록은 대상이 없어서
     * {@code decide} 를 행마다 부를 수 없고, <b>어느 행이 대상인지를 조건이 정한다</b> —
     * 조건이 곧 판정이라 틀리면 남의 주문이 섞인다.
     *
     * <p>그래서 판정 결과에서 범위를 읽어 조건으로 옮긴다. 판정 로직을 다시 쓰지 않는다.
     *
     * @param sellerId 이 셀러 것만. null 이면 볼 수 있는 전부
     */
    public Page find(long viewerId, Long sellerId, String sort, int page, int size) {
        Allowed<Long> visible = visibleSellersFor(viewerId);

        boolean seesEverything = !visible.restricted();
        Long[] sellers = visible.values().toArray(Long[]::new);

        Paging paging = Paging.of(page, size);
        String orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<Summary> items = jdbc.sql("""
                        select so.seller_order_number, o.order_number, so.status,
                               so.shipping_fee, so.created_at,
                               (select count(*) from order_item oi
                                 where oi.seller_order_id = so.seller_order_id) as item_count
                          from seller_order_visible so
                          join shop_order o on o.order_id = so.order_id
                         where (:seesEverything or so.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or so.seller_id = cast(:sellerId as bigint))
                        """
                // 텍스트 블록이 줄 끝 공백을 지워서 "order by" 와 컬럼이 붙는다. 공백을 직접 넣는다.
                + " order by " + orderBy + ", so.seller_order_id desc"
                + " limit :size offset :offset")
                .param("seesEverything", seesEverything)
                .param("sellers", sellers)
                .param("sellerId", sellerId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new Summary(
                        rs.getString("seller_order_number"),
                        rs.getString("order_number"),
                        enumValue(rs.getString("status")),
                        rs.getInt("item_count"),
                        rs.getLong("shipping_fee"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from seller_order_visible so
                         where (:seesEverything or so.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or so.seller_id = cast(:sellerId as bigint))
                        """)
                .param("seesEverything", seesEverything)
                .param("sellers", sellers)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        return new Page(items, paging.page(), paging.size(), total);
    }

    /**
     * 묶음 하나를 펼친다. <b>노출 번호로 찾는다</b>(`D9`).
     *
     * <p>못 보는 것과 없는 것의 답이 같다(`D5` 「권한 실패」). 번호를 훑어서 실재하는 묶음의
     * 지도를 그릴 수 있으면 그게 곧 셀러별 거래 건수다.
     *
     * <p><b>결제 전 묶음은 여기서도 없는 것이다.</b> 뷰를 읽으니 조건을 따로 안 건다.
     */
    public Detail findByNumber(long viewerId, String sellerOrderNumber) {
        Row row = jdbc.sql("""
                        select so.seller_order_id, so.order_id, so.seller_order_number,
                               o.order_number, o.user_id as buyer_user_id, so.seller_id,
                               so.status, so.shipping_fee, so.delivered_at,
                               so.withdrawal_expire_at, so.auto_confirm_at, so.created_at,
                               so.ship_due_at, so.shipped_at,
                               (so.ship_due_at is not null
                                and coalesce(so.shipped_at, now()) > so.ship_due_at) as ship_overdue
                          from seller_order_visible so
                          join shop_order o on o.order_id = so.order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("seller_order_id"),
                        rs.getLong("order_id"),
                        rs.getString("seller_order_number"),
                        rs.getString("order_number"),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id"),
                        rs.getString("status"),
                        rs.getLong("shipping_fee"),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getObject("withdrawal_expire_at", OffsetDateTime.class),
                        rs.getObject("auto_confirm_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("ship_due_at", OffsetDateTime.class),
                        rs.getObject("shipped_at", OffsetDateTime.class),
                        rs.getBoolean("ship_overdue")))
                .optional()
                .orElseThrow(() -> notFound(sellerOrderNumber));

        Decision decision = evaluator.decide(viewerId, "order", "read",
                Target.of(row.buyerUserId(), row.sellerId()));
        if (!decision.allowed()) {
            throw notFound(sellerOrderNumber);
        }

        return new Detail(
                row.sellerOrderNumber(),
                row.orderNumber(),
                enumValue(row.status()),
                row.shippingFee(),
                row.deliveredAt(),
                row.withdrawalExpireAt(),
                row.autoConfirmAt(),
                row.createdAt(),
                row.shipDueAt(),
                row.shippedAt(),
                row.shipOverdue(),
                itemsOf(row.sellerOrderId()),
                actions.allowedActions(viewerId, row.buyerUserId(), row.sellerId(), row.status()),
                decision.canSee(OrderFields.SHIPPING) ? shippingOf(row.orderId()) : null,
                List.copyOf(new TreeSet<>(decision.visibleFieldGroups().values())));
    }

    private static ShopException notFound(String sellerOrderNumber) {
        return new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND,
                "그런 셀러 주문이 없다: " + sellerOrderNumber);
    }

    private List<OrderQuery.Item> itemsOf(long sellerOrderId) {
        return jdbc.sql("""
                        select product_name, option_label, quantity, unit_price_incl_vat, line_amount
                          from order_item
                         where seller_order_id = :sellerOrderId
                         order by order_item_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> new OrderQuery.Item(
                        rs.getString("product_name"),
                        rs.getString("option_label"),
                        rs.getInt("quantity"),
                        rs.getLong("unit_price_incl_vat"),
                        rs.getLong("line_amount")))
                .list();
    }

    /** 셀러가 물건을 보내려면 필요하다. 파기 대상이라 별도 테이블에 있다(`D13`) */
    private OrderQuery.Shipping shippingOf(long orderId) {
        return jdbc.sql("""
                        select receiver_name, receiver_phone, postal_code,
                               address1, address2, delivery_memo
                          from order_shipping
                         where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderQuery.Shipping(
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("postal_code"),
                        rs.getString("address1"),
                        rs.getString("address2"),
                        rs.getString("delivery_memo")))
                .optional()
                .orElse(null);
    }

    /**
     * 이 사람이 목록에서 볼 수 있는 셀러들.
     *
     * <p>{@link Allowed} 로 돌려준다. <b>"전부" 를 빈 집합으로 표현하면 호출자가 그걸 그대로
     * 조건에 넣어서 아무것도 안 나온다</b> — 관리자는 셀러 소속이 없어서 실제로 빈다.
     *
     * <p>여기서 판정을 다시 구현하지 않는다. 대표 대상으로 실제 판정을 돌려서 <b>어느 범위가
     * 열리는지를 답에서 읽는다</b> — `8a` 의 권한 목록과 `ProductQuery` 가 쓰는 방법과 같다.
     */
    private Allowed<Long> visibleSellersFor(long viewerId) {
        // 남의 주문 하나. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, "order", "read", Target.of(-1L, -1L)).allowed()) {
            return Allowed.everything();
        }

        Set<Long> memberOf = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();

        Set<Long> visible = memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, "order", "read", Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());

        if (visible.isEmpty()) {
            // 소속이 있어도 주문 권한이 없으면 볼 목록이 없다. 0건이 아니라 거부다 —
            // 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다.
            throw new ShopException(ErrorCode.ORDER_FORBIDDEN);
        }
        return Allowed.only(visible);
    }

    /** 저장값을 응답 표기로 바꾼다. <b>열거값은 대문자 스네이크다</b>(`D5` 「형식」) */
    private static String enumValue(String storedCode) {
        return storedCode == null ? null : storedCode.toUpperCase(Locale.ROOT);
    }
}
