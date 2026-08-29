package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.projectshop.shop.auth.VisibleFieldGroups;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentStatus;
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
     * @param shipDueAt          발송 기한. 결제 승인 때 박제한다(`D2` R21, 전자상거래법 제15조제1항)
     * @param shippedAt          실제로 보낸 시각. 늦게 보낸 것도 사후에 보인다
     * @param shipOverdue        발송이 늦었나. <b>서버가 판단한다</b> — 화면이 두 시각을 비교하면
     *                           시계 차이만큼 답이 갈리고, 아직 안 보낸 것과 늦게 보낸 것을
     *                           가르는 규칙이 두 벌이 된다
     * @param allowedActions     지금 이 묶음에 할 수 있는 것. 소문자·하이픈이 곧 경로다
     */
    public record SellerOrder(String sellerOrderNumber, String sellerName, String status,
            long shippingFee, OffsetDateTime deliveredAt, OffsetDateTime withdrawalExpireAt,
            OffsetDateTime autoConfirmAt, OffsetDateTime shipDueAt, OffsetDateTime shippedAt,
            boolean shipOverdue, List<Item> items, List<String> allowedActions) {
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
     * 낸 것. {@code payment} 그룹이라 <b>셀러에게는 이 필드가 통째로 빠진다</b>(`V6`, `D2` R18).
     *
     * <p>카드번호가 없는 것이 아니라 <b>담은 적이 없다</b> — 여신전문금융업법 제19조라
     * 결제 표에 그 컬럼이 아예 없다(`V22`). 여기 있는 것이 우리가 가진 전부다.
     */
    public record Payment(String status, String method, String approvalNumber, String cardIssuer,
            String cardLast4, String declineReason, OffsetDateTime paidAt) {
    }

    /**
     * 돌려받은 것 한 줄. {@code refund} 그룹이다(`V24`).
     *
     * <p><b>요청 사유가 없다.</b> 소비자가 쓴 자유 텍스트라 무엇이 들어올지 모르고, 셀러에게
     * 나가는 것이 제3자 제공이다(`D2` R8). 반려 사유는 <b>고객에게 왜 안 됐는지 답하는 값</b>이라
     * 성격이 달라서 환불 상세({@code GET /api/refunds/{번호}})가 내린다.
     *
     * @param overdue 환급 기한을 넘겼나. <b>서버가 계산한다</b> — 화면이 비교하면 시계 차이만큼
     *                답이 갈리고, 그 답이 법 요건이다(`D2` R5)
     */
    public record Refund(String refundNumber, String sellerOrderNumber, String status,
            String reasonCode, long amount, OffsetDateTime dueAt, boolean overdue,
            OffsetDateTime createdAt) {
    }

    /**
     * 계약 시점의 문서 한 줄(`D2` R22, 전자상거래법 제13조제2항 후단).
     *
     * <p><b>본문을 안 싣는다.</b> 상세 하나가 정책 문서 넷을 통째로 지고 가면 응답이 수만 자가
     * 되고, 화면은 대개 제목과 링크만 그린다. 본문은 {@code /api/policies/...} 가 낸다.
     *
     * <p><b>{@code version} 이 이 응답의 핵심이다.</b> 「지금의 문안」이 아니라 그 계약이
     * 무엇에 걸렸는지를 가리키는 값이고, 시행된 판은 못 고치므로(`V27`) 언제 읽어도 같다.
     *
     * @param clause 제13조제2항의 몇 호를 채우나. 문서를 쪼개거나 합쳐도 이 값은 안 바뀐다
     */
    public record ContractDocument(String clause, String code, String title, int version,
            OffsetDateTime effectiveAt) {
    }

    /**
     * 조항 하나를 본문까지 펼친 것(`Q4`).
     *
     * @param body 마크다운. <b>이 주문이 가리키는 판의 본문</b>이지 지금 효력 있는 판이 아니다
     */
    public record ContractDocumentBody(String clause, String code, String title, int version,
            OffsetDateTime effectiveAt, String body) {
    }

    /**
     * 주문 상세. <b>못 보는 것은 null 이 아니라 응답에서 빠진다</b>(`D5`).
     *
     * @param shipping 배송지. {@code shipping} 그룹
     * @param payment  결제. {@code payment} 그룹. <b>아직 안 낸 주문은 그룹이 보여도 비어 있다</b> —
     *                 `D5` 가 "필드가 있는데 값이 없으면 진짜로 값이 없는 것" 이라고 갈라 뒀고,
     *                 여기서는 그룹 목록에 {@code payment} 가 있느냐가 그 둘을 가른다
     * @param refunds  환불. {@code refund} 그룹. <b>없으면 빈 배열이고 못 보면 필드가 빠진다</b> —
     *                 같은 이유로 그룹 목록이 그 둘을 가른다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String orderNumber, String status, long totalAmount,
            long shippingFeeTotal, long payableAmount, OffsetDateTime createdAt,
            List<SellerOrder> sellerOrders, List<HistoryEntry> history, Shipping shipping,
            Payment payment, List<Refund> refunds, List<ContractDocument> contractDocuments,
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
                        orderPaymentStatus(rs.getString("status")),
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
                orderPaymentStatus(order.status()),
                order.totalAmount(),
                order.shippingFeeTotal(),
                order.payableAmount(),
                order.createdAt(),
                sellerOrdersOf(order.orderId(), userId, order.userId()),
                historyOf(order.orderId()),
                decision.canSee(OrderFields.SHIPPING) ? shippingOf(order.orderId()) : null,
                decision.canSee(OrderFields.PAYMENT) ? paymentOf(order.orderId()) : null,
                decision.canSee(OrderFields.REFUND) ? refundsOf(order.orderId()) : null,
                // 필드 그룹에 안 건다. 계약 조건은 사는 사람에게도 파는 사람에게도
                // 같은 것이 걸려 있고, 보는 사람에 따라 갈릴 것이 아니다(`D23` 「어느 쪽을 언제 쓰나」).
                contractDocumentsOf(order.orderId()),
                VisibleFieldGroups.of(decision, OrderFields.values()));
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
                               so.delivered_at, so.withdrawal_expire_at, so.auto_confirm_at,
                               so.ship_due_at, so.shipped_at,
                               (so.ship_due_at is not null
                                and coalesce(so.shipped_at, now()) > so.ship_due_at) as ship_overdue
                          from seller_order so
                          join seller s on s.seller_id = so.seller_id
                         where so.order_id = :orderId
                         order by so.seller_order_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new SellerOrder(
                        rs.getString("seller_order_number"),
                        rs.getString("seller_name"),
                        shipmentStatus(rs.getString("status")),
                        rs.getLong("shipping_fee"),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getObject("withdrawal_expire_at", OffsetDateTime.class),
                        rs.getObject("auto_confirm_at", OffsetDateTime.class),
                        rs.getObject("ship_due_at", OffsetDateTime.class),
                        rs.getObject("shipped_at", OffsetDateTime.class),
                        rs.getBoolean("ship_overdue"),
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
                        OrderTransitions.statusName(rs.getString("from_status")),
                        OrderTransitions.statusName(rs.getString("to_status")),
                        actorType(rs.getString("actor_type")),
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
     * 마지막 결제 하나. <b>거절이 여러 번 날 수 있어서 목록이 아니라 최근 것이다.</b>
     *
     * <p>승인이 나면 주문이 {@code paid} 로 가서 더는 결제가 안 생긴다(`D7`) —
     * 승인이 있는 주문에서 최근 행은 언제나 그 승인이다. 거절만 쌓인 주문은 마지막 거절이 보이고,
     * 그것이 화면이 물어보는 것("왜 안 됐나")에 답하는 값이다.
     *
     * <p>지나간 시도 전체는 여기서 안 내린다. 그것을 보는 자리는 관리자 조회고
     * {@code payment:read} 권한이 거기 걸린다(`V3`).
     */
    /**
     * 이 주문에 걸린 계약 문서(`D2` R22).
     *
     * <p>두 표에서 온다 — 청약철회·분쟁 처리는 {@code policy_document} 고 약관은
     * {@code consent_item} 이다. 한 목록으로 합치는 이유는 <b>보는 쪽에는 같은 것</b>이라서고,
     * 어느 표에서 왔는지는 화면이 알 필요가 없다.
     *
     * <p>호 순서로 낸다. 법이 정한 순서라 화면이 다시 정렬할 것이 없다.
     */
    private List<ContractDocument> contractDocumentsOf(long orderId) {
        return jdbc.sql("""
                        select d.clause,
                               coalesce(p.code, c.code)                 as code,
                               coalesce(p.title, c.title)               as title,
                               coalesce(p.version, c.version)           as version,
                               coalesce(p.effective_at, c.effective_at) as effective_at
                          from order_contract_document d
                          left join policy_document p
                                 on p.policy_document_id = d.policy_document_id
                          left join consent_item c
                                 on c.consent_item_id = d.consent_item_id
                         where d.order_id = :orderId
                         order by case d.clause
                                      when 'withdrawal' then 1
                                      when 'exchange'   then 2
                                      when 'dispute'    then 3
                                      else 4
                                  end
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ContractDocument(
                        contractClause(rs.getString("clause")),
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getInt("version"),
                        rs.getObject("effective_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * 계약내용 서면 하나를 <b>본문까지</b> 펼친다(`Q4`, `D2` R22).
     *
     * <p>전자상거래법 제13조제2항 후단이 「계약내용에 관한 서면을 재화등을 공급할 때까지
     * <b>교부</b>」라고 한다. 목록만으로는 교부가 아니다 — 제목과 판만 알려 주고 본문을 못 읽으면
     * 그 사람이 무엇에 계약했는지는 여전히 모른다.
     *
     * <p><b>지금 효력 있는 판이 아니라 이 주문이 가리키는 판이다.</b> 개정됐으면 둘이 다르고,
     * 최신판을 내주면 <b>그 사이 우리가 고친 것을 들이미는 꼴</b>이 된다
     * ({@code ConsentService.readMine} 이 같은 판단을 했다).
     *
     * <p><b>목록에 본문을 안 싣는 이유</b>는 `5k` 와 같다 — 약관 전문이 조항 수만큼 딸려 나오면
     * 주문 상세가 무거워진다. 펼칠 때 하나씩 받는다.
     *
     * <p>권한은 주문 상세와 같은 것을 본다. 못 보는 주문은 없는 주문과 같은 404 다(`D5`).
     */
    public ContractDocumentBody contractDocument(long userId, String orderNumber, String clause) {
        long orderId = requireReadableOrderId(userId, orderNumber);

        return jdbc.sql("""
                        select d.clause,
                               coalesce(p.code, c.code)                 as code,
                               coalesce(p.title, c.title)               as title,
                               coalesce(p.version, c.version)           as version,
                               coalesce(p.effective_at, c.effective_at) as effective_at,
                               coalesce(p.body, c.body)                 as body
                          from order_contract_document d
                          left join policy_document p
                                 on p.policy_document_id = d.policy_document_id
                          left join consent_item c
                                 on c.consent_item_id = d.consent_item_id
                         where d.order_id = :orderId and d.clause = :clause
                        """)
                .param("orderId", orderId)
                .param("clause", clause.toLowerCase(Locale.ROOT))
                .query((rs, rowNum) -> new ContractDocumentBody(
                        contractClause(rs.getString("clause")),
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getInt("version"),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("body")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.POLICY_NOT_FOUND,
                        "그 주문에 그런 조항이 없다: " + clause));
    }

    /**
     * 주문 상세와 같은 판정을 한 번 더 쓴다.
     *
     * <p>같은 물음("이 사람이 이 주문을 볼 수 있나")에 답하는 자리가 둘이 되면
     * 한쪽 규칙을 고치는 사람이 다른 쪽을 못 본다.
     */
    private long requireReadableOrderId(long userId, String orderNumber) {
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

        if (!evaluator.decide(userId, "order", "read", Target.ownedBy(order.userId())).allowed()) {
            throw new ShopException(ErrorCode.ORDER_NOT_FOUND, "그런 주문이 없다: " + orderNumber);
        }
        return order.orderId();
    }

    /**
     * 이 주문에서 나간 환불. <b>반려된 것도 내린다.</b>
     *
     * <p>목록에서 빼면 「요청했는데 아무 일도 안 일어난」 것처럼 보인다 —
     * 반려는 결과지 없던 일이 아니고, 고객이 물어볼 것이 그 줄이다.
     *
     * <p>주문 전체를 훑는다. 환불은 셀러 묶음 단위지만 상세가 주문 하나라 여기 모아 내리고,
     * 어느 묶음 것인지는 {@code sellerOrderNumber} 가 가리킨다.
     */
    private List<Refund> refundsOf(long orderId) {
        return jdbc.sql("""
                        select r.refund_number, so.seller_order_number, r.status, r.reason_code,
                               r.amount, r.due_at, r.created_at,
                               (r.status = 'requested' and r.due_at < now()) as overdue
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                         where so.order_id = :orderId
                         order by r.refund_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Refund(
                        rs.getString("refund_number"),
                        rs.getString("seller_order_number"),
                        enumValue(rs.getString("status")),
                        enumValue(rs.getString("reason_code")),
                        rs.getLong("amount"),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getBoolean("overdue"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private Payment paymentOf(long orderId) {
        return jdbc.sql("""
                        select p.status, p.method, p.approval_number,
                               c.card_issuer, c.card_last4,
                               p.decline_reason, p.created_at
                          from payment p
                          -- 카드 정보는 거래 종료 여섯 달 뒤에 사라진다(`D2` R9 보존분 분리).
                          -- 그 뒤로는 수단만 남고 화면은 발급사 자리를 안 그린다.
                          left join payment_card c on c.payment_id = p.payment_id
                         where p.order_id = :orderId
                         order by p.created_at desc, p.payment_id desc
                         limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Payment(
                        paymentStatus(rs.getString("status")),
                        paymentMethod(rs.getString("method")),
                        rs.getString("approval_number"),
                        rs.getString("card_issuer"),
                        rs.getString("card_last4"),
                        rs.getString("decline_reason"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
    }

    /**
     * 저장값을 응답 표기로 바꾼다. <b>열거값은 대문자 스네이크다</b>(`D5` 「형식」).
     *
     * <p>{@code Locale.ROOT} 를 쓴다. 기본 로케일이면 터키어에서 {@code i} 가 {@code İ} 가 돼서
     * 같은 코드가 서버 설정에 따라 다르게 나간다.
     *
     * <p><b>이건 검증을 안 한다.</b> 표기만 바꾸므로 DB 에 모르는 값이 들어와 있으면
     * 그대로 대문자로 올려 보낸다 — 화면이 처음 보는 값을 받는다.
     *
     * <p>옮긴 것이 일곱이다 — 주문 상태 셋은 {@link #orderPaymentStatus}·{@link #shipmentStatus}·
     * {@link OrderTransitions#statusName}(`43a-7`), 주체와 조항은 {@link #actorType}·
     * {@link #contractClause}(`43a-8`), 결제는 {@link #paymentStatus}·{@link #paymentMethod}(`43a-9`).
     *
     * <p><b>여기 남은 둘</b>은 환불의 {@code status}·{@code reason_code} 다. 열거형이 아직
     * 없어서지 안 필요해서가 아니다 — `D23` 「Java 표현」이 생 문자열을 금지하고 둘 다 코드가
     * 값마다 분기한다({@code RefundService.APPROVED_CODE} 가 그 분기다).
     * {@link com.projectshop.shop.payment.RefundQuery} 가 같은 둘을 읽어서 `43a-11` 로 갈랐다.
     */
    private static String enumValue(String storedCode) {
        return storedCode == null ? null : storedCode.toUpperCase(Locale.ROOT);
    }

    /** 결제 시도 한 건의 결과({@code payment.status}). 주문의 결제 층 상태와 다른 값이다 */
    private static String paymentStatus(String storedCode) {
        return storedCode == null ? null : PaymentStatus.of(storedCode).name();
    }

    /** 무엇으로 냈나({@code payment.method}) */
    private static String paymentMethod(String storedCode) {
        return storedCode == null ? null : PaymentMethod.of(storedCode).name();
    }

    /** 이력 한 줄을 누가 일으켰나({@code order_status_history.actor_type}) */
    private static String actorType(String storedCode) {
        return storedCode == null ? null : ActorType.of(storedCode).name();
    }

    /** 제13조제2항의 호({@code order_contract_document.clause}) */
    private static String contractClause(String storedCode) {
        return storedCode == null ? null : ContractClause.of(storedCode).name();
    }

    /**
     * 주문의 결제 층 상태({@code shop_order.status}). 열거형을 지나 모르는 값에 터진다.
     *
     * <p><b>{@link #paymentStatus} 와 다른 값이다.</b> 이쪽은 주문이 어디까지 왔나
     * ({@code payment_pending}·{@code paid})고, 저쪽은 승인 시도 한 건의 결과
     * ({@code approved}·{@code failed})다. 이름이 부딪쳐서 갈랐다 —
     * <b>부딪쳤다는 것 자체가 둘이 헷갈린다는 증거다</b>(`43a-9`).
     */
    private static String orderPaymentStatus(String storedCode) {
        return storedCode == null ? null : OrderTransitions.Payment.of(storedCode).name();
    }

    /** 배송 층의 상태({@code seller_order.status}) */
    private static String shipmentStatus(String storedCode) {
        return storedCode == null ? null : OrderTransitions.Shipment.of(storedCode).name();
    }
}
