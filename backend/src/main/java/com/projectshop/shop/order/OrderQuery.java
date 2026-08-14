package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 산 사람이 자기 주문을 찾아 본다.
 *
 * <p><b>셀러가 보는 경로는 여기가 아니다.</b> 조건의 성격이 달라서다 —
 * 이쪽은 {@code user_id = 나} 하나로 끝나고 셀러 쪽은 소속과 미결제 여부를 같이 봐야 한다.
 * 한 쿼리로 합치면 {@code or} 하나가 틀렸을 때 <b>남의 주문이 목록에 섞이고 오류는 안 난다</b>
 * (`ProductQuery` 가 같은 이유로 갈라져 있다).
 *
 * <p><b>상세가 상태 이력을 같이 내린다.</b> 전자상거래법 제6조 제3항이 소비자에게 거래기록을
 * 열람할 방법을 요구한다(`D2` R6). 화면이 따로 요청해서 붙이는 방식이면
 * 그 화면을 안 만든 동안 열람 방법이 없는 상태가 된다.
 */
@Service
public class OrderQuery {

    /**
     * 정렬 가능한 필드. 요청은 API 이름으로 오고 값은 실제 컬럼식이다(`D14`).
     *
     * <p>금액순을 안 연다. 주문 목록에서 금액으로 정렬하는 화면이 아직 없고,
     * 쓰는 곳 없이 열면 인덱스만 하나 더 지고 간다.
     */
    private static final Map<String, String> SORTABLE = Map.of("created_at", "o.created_at");

    private static final String DEFAULT_SORT = "created_at,desc";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final OrderActionService actions;

    OrderQuery(JdbcClient jdbc, PermissionEvaluator evaluator, OrderActionService actions) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.actions = actions;
    }

    /** 목록 한 줄. 무엇을 샀는지는 상세가 답한다 — 목록에 상품을 붙이면 화면마다 다른 요약이 필요해진다 */
    public record Summary(String orderNumber, String status, long payableAmount,
            int itemCount, OffsetDateTime createdAt) {
    }

    public record Page(List<Summary> items, int page, int size, long total) {
    }

    /** 산 것 한 줄. 주문 시점에 박제된 값이라 상품이 바뀌어도 안 바뀐다 */
    public record Item(String productName, String optionLabel, int quantity,
            long unitPriceInclVat, long lineAmount) {
    }

    /**
     * 셀러별 배송 묶음. 상태와 기한이 여기 붙는다(`D7`).
     *
     * <p><b>노출 번호가 여기 나간다.</b> 구매확정·반품접수의 단위가 주문 전체가 아니라 이 묶음이라
     * (`D7`: 취소·반품의 최소 단위는 셀러 묶음) 이것이 없으면 화면이 동작을 못 부른다.
     * 내부 {@code seller_order_id} 는 여전히 안 나간다(`D9`).
     *
     * @param withdrawalExpireAt 청약철회 기한. 배송완료 때 박제한 값이다
     * @param autoConfirmAt      자동 구매확정 예정일. 배치가 이 시각을 보고 옮긴다
     * @param allowedActions     지금 이 묶음에 할 수 있는 것. 소문자·하이픈이 곧 경로다
     */
    public record SellerOrder(String sellerOrderNumber, String sellerName, String status,
            long shippingFee, OffsetDateTime deliveredAt, OffsetDateTime withdrawalExpireAt,
            OffsetDateTime autoConfirmAt, List<Item> items, List<String> allowedActions) {
    }

    /**
     * 이력 한 줄. <b>사람 이름을 안 담는다</b> — 누가 옮겼나는 역할이면 충분하고,
     * 계정이 파기돼도 이력은 5년 남는다(`D13`).
     *
     * @param sellerName 배송 층 이력이면 어느 셀러 것인가. 결제 층 이력이면 null
     */
    public record HistoryEntry(String sellerName, String fromStatus, String toStatus,
            String actorType, OffsetDateTime occurredAt) {
    }

    /** 받는 사람. {@code shipping} 그룹이라 못 보는 역할에는 이 필드가 통째로 빠진다 */
    public record Shipping(String receiverName, String receiverPhone, String postalCode,
            String address1, String address2, String deliveryMemo) {
    }

    /**
     * 주문 상세. <b>못 보는 것은 null 이 아니라 응답에서 빠진다</b>(`D5`).
     *
     * @param shipping 배송지. {@code shipping} 그룹
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String orderNumber, String status, long totalAmount,
            long shippingFeeTotal, long payableAmount, OffsetDateTime createdAt,
            List<SellerOrder> sellerOrders, List<HistoryEntry> history, Shipping shipping,
            @JsonProperty("_visible_field_groups") List<String> visibleFieldGroups) {
    }

    /** DB 에서 읽은 주문 머리. 무엇을 내릴지는 판정이 정한다 */
    private record OrderRow(long orderId, String orderNumber, long userId, String status,
            long totalAmount, long shippingFeeTotal, long payableAmount, OffsetDateTime createdAt) {
    }

    /** 항목이 어느 셀러 묶음에 붙는지를 들고 오는 중간 값. {@code seller_order_id} 는 응답에 안 나간다(`D9`) */
    private record ItemRow(long sellerOrderId, Item item) {
    }

    /**
     * 내가 산 것을 시간순으로 본다.
     *
     * <p><b>권한이 없으면 빈 페이지다.</b> 오류가 아닌 이유는 목록의 물음이
     * "내가 볼 수 있는 것이 무엇인가" 라서다 — 볼 수 있는 것이 없으면 답은 0건이다.
     * 조용히 넘어가지도 않는다. 판정이 거부를 감사 로그에 남긴다(`4b`).
     */
    public Page findMine(long userId, String sort, int page, int size) {
        Paging paging = Paging.of(page, size);
        String orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        if (!evaluator.decide(userId, "order", "read", Target.ownedBy(userId)).allowed()) {
            return new Page(List.of(), paging.page(), paging.size(), 0);
        }

        List<Summary> items = jdbc.sql("""
                        select o.order_number, o.status, o.payable_amount, o.created_at,
                               (select count(*)
                                  from order_item oi
                                  join seller_order so on so.seller_order_id = oi.seller_order_id
                                 where so.order_id = o.order_id) as item_count
                          from shop_order o
                         where o.user_id = :userId
                        """
                // 텍스트 블록이 줄 끝 공백을 지워서 "order by" 와 컬럼이 붙는다. 공백을 직접 넣는다.
                + " order by " + orderBy + ", o.order_id desc"
                + " limit :size offset :offset")
                .param("userId", userId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new Summary(
                        rs.getString("order_number"),
                        enumValue(rs.getString("status")),
                        rs.getLong("payable_amount"),
                        rs.getInt("item_count"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("select count(*) from shop_order where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();

        return new Page(items, paging.page(), paging.size(), total);
    }

    /**
     * 주문 하나를 펼쳐 본다. <b>노출 번호로 찾는다</b>(`D9`).
     *
     * <p><b>못 보는 주문은 없는 주문과 같은 답을 준다</b>(`D5` 「권한 실패」).
     * 403 을 주면 번호를 훑어서 실재하는 주문의 지도를 그릴 수 있고, 그게 곧 주문 수와 증가 속도다.
     */
    public Detail findByNumber(long userId, String orderNumber) {
        OrderRow order = jdbc.sql("""
                        select order_id, order_number, user_id, status, total_amount,
                               shipping_fee_total, payable_amount, created_at
                          from shop_order
                         where order_number = :orderNumber
                        """)
                .param("orderNumber", orderNumber)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("order_id"),
                        rs.getString("order_number"),
                        rs.getLong("user_id"),
                        rs.getString("status"),
                        rs.getLong("total_amount"),
                        rs.getLong("shipping_fee_total"),
                        rs.getLong("payable_amount"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND,
                        "그런 주문이 없다: " + orderNumber));

        Decision decision = evaluator.decide(userId, "order", "read",
                Target.ownedBy(order.userId()));
        if (!decision.allowed()) {
            throw new ShopException(ErrorCode.ORDER_NOT_FOUND, "그런 주문이 없다: " + orderNumber);
        }

        return new Detail(
                order.orderNumber(),
                enumValue(order.status()),
                order.totalAmount(),
                order.shippingFeeTotal(),
                order.payableAmount(),
                order.createdAt(),
                sellerOrdersOf(order.orderId(), userId, order.userId()),
                historyOf(order.orderId()),
                decision.canSee(OrderFields.SHIPPING) ? shippingOf(order.orderId()) : null,
                List.copyOf(new TreeSet<>(decision.visibleFieldGroups().values())));
    }

    /**
     * 셀러별 묶음과 그 안의 항목.
     *
     * <p>항목을 묶음마다 한 번씩 조회하지 않는다. 셀러가 셋이면 쿼리가 넷이 되고,
     * 그 모양은 셀러 수가 늘 때마다 조용히 느려진다.
     */
    private List<SellerOrder> sellerOrdersOf(long orderId, long viewerId, long buyerUserId) {
        Map<Long, List<Item>> itemsBySellerOrder = new LinkedHashMap<>();
        jdbc.sql("""
                        select oi.seller_order_id, oi.product_name, oi.option_label,
                               oi.quantity, oi.unit_price_incl_vat, oi.line_amount
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.order_id = :orderId
                         order by oi.order_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ItemRow(
                        rs.getLong("seller_order_id"),
                        new Item(
                                rs.getString("product_name"),
                                rs.getString("option_label"),
                                rs.getInt("quantity"),
                                rs.getLong("unit_price_incl_vat"),
                                rs.getLong("line_amount"))))
                .list()
                .forEach(row -> itemsBySellerOrder
                        .computeIfAbsent(row.sellerOrderId(), key -> new ArrayList<>())
                        .add(row.item()));

        return jdbc.sql("""
                        select so.seller_order_id, so.seller_order_number, so.seller_id,
                               s.name as seller_name, so.status, so.shipping_fee,
                               so.delivered_at, so.withdrawal_expire_at, so.auto_confirm_at
                          from seller_order so
                          join seller s on s.seller_id = so.seller_id
                         where so.order_id = :orderId
                         order by so.seller_order_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new SellerOrder(
                        rs.getString("seller_order_number"),
                        rs.getString("seller_name"),
                        enumValue(rs.getString("status")),
                        rs.getLong("shipping_fee"),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getObject("withdrawal_expire_at", OffsetDateTime.class),
                        rs.getObject("auto_confirm_at", OffsetDateTime.class),
                        List.copyOf(itemsBySellerOrder.getOrDefault(
                                rs.getLong("seller_order_id"), List.of())),
                        actions.allowedActions(viewerId, buyerUserId,
                                rs.getLong("seller_id"), rs.getString("status"))))
                .list();
    }

    /**
     * 결제 층과 배송 층 이력을 한 줄로 세운다(`D2` R6).
     *
     * <p>두 층을 나눠 내리지 않는다. 소비자가 보는 것은 주문 하나의 생애 전체고,
     * 층마다 나누면 화면이 매번 다시 합쳐야 한다 — 한쪽을 빠뜨려도 화면에 오류로 안 드러난다.
     */
    private List<HistoryEntry> historyOf(long orderId) {
        return jdbc.sql("""
                        select s.name as seller_name, h.from_status, h.to_status,
                               h.actor_type, h.occurred_at
                          from order_status_history h
                          left join seller_order so
                                 on so.seller_order_id = h.seller_order_id
                          left join seller s on s.seller_id = so.seller_id
                         where h.order_id = :orderId or so.order_id = :orderId
                         order by h.occurred_at, h.order_status_history_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new HistoryEntry(
                        rs.getString("seller_name"),
                        enumValue(rs.getString("from_status")),
                        enumValue(rs.getString("to_status")),
                        enumValue(rs.getString("actor_type")),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    /** 배송지는 파기 대상이라 별도 테이블이다(`D13`). 5년 지난 주문은 여기가 비어 있다 */
    private Shipping shippingOf(long orderId) {
        return jdbc.sql("""
                        select receiver_name, receiver_phone, postal_code,
                               address1, address2, delivery_memo
                          from order_shipping
                         where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Shipping(
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
     * 저장값을 응답 표기로 바꾼다. <b>열거값은 대문자 스네이크다</b>(`D5` 「형식」).
     *
     * <p>{@code Locale.ROOT} 를 쓴다. 기본 로케일이면 터키어에서 {@code i} 가 {@code İ} 가 돼서
     * 같은 코드가 서버 설정에 따라 다르게 나간다.
     */
    private static String enumValue(String storedCode) {
        return storedCode == null ? null : storedCode.toUpperCase(Locale.ROOT);
    }
}
