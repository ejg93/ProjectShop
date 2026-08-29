package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.payment.PaymentService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 공급 기한이 박제되나(`D2` R21, 전자상거래법 제15조제1항).
 *
 * <p><b>선지급식 통신판매는 대금을 지급한 날부터 3영업일 이내에 공급에 필요한 조치</b>를 해야 한다.
 * 우리 결제가 선지급식이고, 우리 상태머신에서 그 조치는 {@code preparing → shipping} 이다.
 *
 * <p><b>중개자 고지로 안 빠져나간다</b> — 제20조의2제3항이 못 면하는 범위에 제15조를 넣는다.
 *
 * <p>이 테스트가 없으면 지키는 것이 아무것도 없다. {@code seller_order} 는 기한을 셋 박제해
 * 왔는데 이것만 없었고, <b>없는 것은 화면에도 오류에도 안 드러난다.</b>
 */
@DisplayName("공급 기한")
class ShipDeadlineTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int ORDERED = 1;
    private static final int STOCK = 10;

    /** 법정 기한. 공급시기 약정이 없으면 이것이 걸린다 */
    private static final int LEGAL_DAYS = 3;

    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private BusinessCalendar calendar;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long sellerId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("ship-due-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        sellerId = fixture.insertSeller("s-ship-due", "발송셀러");
        fixture.verifySeller(sellerId);
    }

    @Nested
    @DisplayName("결제가 승인되면")
    class OnPayment {

        @Test
        @DisplayName("법정 3영업일로 박제된다")
        void freezesTheLegalDeadline() {
            long orderId = placeAndPay(insertSku(null));

            assertThat(shipDueDate(orderId))
                    .as("제15조제1항 — 대금을 지급한 날부터 3영업일이다")
                    .isEqualTo(calendar.plusBusinessDays(today(), LEGAL_DAYS));
        }

        /**
         * <b>결제 전에는 기한이 없다.</b> 기산점이 대금 지급일이라 낼 때까지 셀 것이 없고,
         * {@code seller_order_ship_due_check} 가 그 짝을 강제한다(`V26`).
         */
        @Test
        @DisplayName("결제 전에는 기한이 비어 있다")
        void leavesItEmptyBeforePayment() {
            long orderId = place(insertSku(null));

            assertThat(shipDueIsNull(orderId)).isTrue();
        }

        /**
         * 공급시기를 따로 약정하면 법정 기한이 안 걸린다(제15조제1항 단서).
         *
         * <p>주문제작·예약판매가 그것이다. <b>약정이 없으면 3영업일이 그대로 걸리므로</b>
         * 주문제작 상품이 전부 지연으로 표시되는 것을 이 값이 막는다.
         */
        @Test
        @DisplayName("공급시기 약정이 있으면 그 날수를 쓴다")
        void honoursTheAgreedLeadTime() {
            long orderId = placeAndPay(insertSku(10));

            assertThat(shipDueDate(orderId))
                    .as("제15조제1항 단서 — 따로 약정한 공급시기가 있으면 법정 기한이 안 걸린다")
                    .isEqualTo(calendar.plusBusinessDays(today(), 10));
        }

        /**
         * <b>약정이 있었는지가 묶음에 남나</b>(`14c`).
         *
         * <p>기한은 `supply_lead_days` 로 계산되는데 그 값은 <b>결과</b>라
         * 3 이 「약정 3영업일」인지 「약정이 없어서 법정 3영업일」인지 안 갈린다.
         * 나중에 「이 주문에 약정이 있었나」를 물으면 상품의 <b>지금</b> 값을 봐야 하고,
         * 그건 셀러가 그 사이 바꿨을 수 있다 — 리드타임을 박제한 이유가 바로 그것이었다(`V26`).
         */
        @Test
        @DisplayName("약정이 없으면 약정 칸이 빈다")
        void leavesAgreementEmptyWithoutOne() {
            long orderId = placeAndPay(insertSku(null));

            assertThat(agreedLeadDays(orderId))
                    .as("법정 기한이 걸린 것과 3영업일을 약정한 것은 다른 사실이다")
                    .isNull();
        }

        @Test
        @DisplayName("약정이 있으면 그 날수가 남는다")
        void keepsTheAgreedLeadTime() {
            long orderId = placeAndPay(insertSku(10));

            assertThat(agreedLeadDays(orderId)).isEqualTo(10);
        }

        /**
         * 한 셀러 묶음은 한 번에 나간다.
         *
         * <p>가장 짧은 것을 쓰면 <b>아직 준비 안 된 항목이 있는데 기한이 지난 것</b>이 되고,
         * 그건 셀러가 못 지킬 기한이다.
         */
        @Test
        @DisplayName("묶음 안에서 가장 긴 약정을 쓴다")
        void takesTheLongestLeadTimeInTheBundle() {
            long orderId = placeAndPay(List.of(insertSku(2), insertSku(9)));

            assertThat(shipDueDate(orderId))
                    .as("가장 늦게 준비되는 항목이 그 묶음의 발송 시점을 정한다")
                    .isEqualTo(calendar.plusBusinessDays(today(), 9));
        }

        /**
         * <b>주문 시점에 박제한다.</b> 가격·수수료율과 같은 이유다 —
         * 셀러가 나중에 값을 바꿔도 지나간 주문의 기한이 안 흔들려야 한다(`D10`).
         */
        @Test
        @DisplayName("주문 뒤에 상품을 고쳐도 안 흔들린다")
        void doesNotFollowLaterProductChanges() {
            long skuId = insertSku(10);
            long orderId = place(List.of(skuId));

            jdbc.sql("""
                            update product set supply_lead_days = 40
                             where product_id = (select product_id from sku where sku_id = :skuId)
                            """)
                    .param("skuId", skuId)
                    .update();

            pay(orderId);

            assertThat(shipDueDate(orderId))
                    .as("묶음에 박제한 값을 쓴다. 상품을 다시 읽으면 지나간 주문이 흔들린다")
                    .isEqualTo(calendar.plusBusinessDays(today(), 10));
        }
    }

    @Nested
    @DisplayName("발송하면")
    class OnShipping {

        @Test
        @DisplayName("보낸 시각이 남는다")
        void recordsWhenItWasShipped() {
            long orderId = placeAndPay(insertSku(null));
            ship(orderId);

            assertThat(shippedAt(orderId))
                    .as("이것이 없으면 늦게라도 보내는 순간 지연의 흔적이 사라진다")
                    .isNotNull();
        }

        /**
         * <b>「지금 늦고 있는 것」과 「늦었던 것」이 다르다.</b>
         *
         * <p>조건을 {@code preparing} 인 것만으로 잡으면 상습적으로 늦는 셀러와
         * 한 번 늦은 셀러가 데이터에서 같아 보이고, 그러면 제재의 근거가 없다.
         */
        @Test
        @DisplayName("늦게 보낸 사실이 발송 뒤에도 남는다")
        void keepsTheOverdueFactAfterShipping() {
            long orderId = placeAndPay(insertSku(null));
            expireDeadline(orderId);
            ship(orderId);

            assertThat(overdue(orderId))
                    .as("shipped_at 과 ship_due_at 의 비교로 남는다")
                    .isTrue();
        }

        @Test
        @DisplayName("기한 안에 보내면 지연이 아니다")
        void isNotOverdueWhenShippedInTime() {
            long orderId = placeAndPay(insertSku(null));
            ship(orderId);

            assertThat(overdue(orderId)).isFalse();
        }

        @Test
        @DisplayName("아직 안 보냈는데 기한이 지나면 지연이다")
        void isOverdueWhileStillPreparing() {
            long orderId = placeAndPay(insertSku(null));
            expireDeadline(orderId);

            assertThat(overdue(orderId))
                    .as("보낸 시각이 없으면 지금 시각과 비교한다")
                    .isTrue();
        }
    }

    /**
     * 기한이 비는 것을 DB 가 막나(`Q2`, `V30`).
     *
     * <p><b>앱 검증만으로는 부족하다.</b> 지금 박제를 부르는 자리가 하나뿐이라 안 빠뜨리는데,
     * 새 입구가 생기면 빠뜨린다(`D23` 축 2). 빠뜨리면 조용하다 —
     * {@code seller_order_ship_overdue_idx} 가 {@code is not null} 로 걸러서
     * <b>기한을 넘긴 묶음이 「지금 늦고 있는 것」 조회에서 사라진다.</b>
     */
    @Nested
    @DisplayName("기한이 비면")
    class MissingDeadline {

        @Test
        @DisplayName("결제된 주문에 기한 없는 묶음이 남으면 커밋이 막힌다")
        void rejectsPaidOrderWithoutDeadline() {
            long orderId = placeAndPay(insertSku(null));

            // 박제를 빠뜨린 새 입구를 흉내 낸다.
            jdbc.sql("update seller_order set ship_due_at = null where order_id = :orderId")
                    .param("orderId", orderId)
                    .update();
            jdbc.sql("update shop_order set status = 'paid' where order_id = :orderId")
                    .param("orderId", orderId)
                    .update();

            // 지연 제약이라 고치는 순간이 아니라 커밋할 때 걸린다. 테스트는 커밋을 안 하므로
            // 밀린 검사를 여기서 당겨 돌린다(`OrderSchemaTest` 와 같은 수법).
            assertThatThrownBy(() -> jdbc.sql("set constraints all immediate").update())
                    .as("발송 기한 없이 결제된 주문이 남으면 안 된다(D2 R21)")
                    .hasMessageContaining("발송 기한이 없는 묶음");
        }
    }

    /** 기한을 지난 것으로 민다. 하루 전으로 당기면 지금이 이미 넘긴 시점이다 */
    private void expireDeadline(long orderId) {
        jdbc.sql("""
                        update seller_order set ship_due_at = now() - interval '1 day'
                         where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void ship(long orderId) {
        statuses.moveShipment(bundleId(orderId), OrderTransitions.Shipment.SHIPPING,
                OrderStatusService.Actor.seller(buyerId));
    }

    /** 묶음에 박제된 약정 날수. 약정이 없었으면 {@code null} 이다 */
    private Integer agreedLeadDays(long orderId) {
        return jdbc.sql("select agreed_lead_days from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getObject("agreed_lead_days", Integer.class))
                // `single()` 은 매핑 결과가 null 이면 터진다. 여기서는 null 이 답이라 목록으로 받는다.
                .list()
                .getFirst();
    }

    private LocalDate shipDueDate(long orderId) {
        return jdbc.sql("select ship_due_at from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getObject("ship_due_at", OffsetDateTime.class))
                .single()
                .atZoneSameInstant(BusinessCalendar.ZONE)
                .toLocalDate();
    }

    private boolean shipDueIsNull(long orderId) {
        return jdbc.sql("select ship_due_at is null from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }

    private OffsetDateTime shippedAt(long orderId) {
        return jdbc.sql("select shipped_at from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getObject("shipped_at", OffsetDateTime.class))
                .single();
    }

    /** 조회가 쓰는 판정식과 같은 것을 쓴다. 두 벌이면 화면과 테스트가 다른 답을 낸다 */
    private boolean overdue(long orderId) {
        return jdbc.sql("""
                        select ship_due_at is not null
                               and coalesce(shipped_at, now()) > ship_due_at
                          from seller_order where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }

    private long bundleId(long orderId) {
        return jdbc.sql("select seller_order_id from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessCalendar.ZONE);
    }

    private long placeAndPay(long skuId) {
        return placeAndPay(List.of(skuId));
    }

    private long placeAndPay(List<Long> skuIds) {
        long orderId = place(skuIds);
        pay(orderId);
        return orderId;
    }

    private void pay(long orderId) {
        String orderNumber = jdbc.sql("select order_number from shop_order where order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(orderNumber, "card", GOOD_CARD));
    }

    private long place(long skuId) {
        return place(List.of(skuId));
    }

    private long place(List<Long> skuIds) {
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql(
                                "insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyerId)
                        .query(Long.class)
                        .single());

        List<Long> cartItemIds = skuIds.stream()
                .map(skuId -> jdbc.sql("""
                                insert into cart_item (cart_id, sku_id, quantity)
                                values (:cartId, :skuId, :quantity)
                                returning cart_item_id
                                """)
                        .param("cartId", cartId)
                        .param("skuId", skuId)
                        .param("quantity", ORDERED)
                        .query(Long.class)
                        .single())
                .toList();

        return orderService.create(buyerId, new OrderService.Command(cartItemIds,
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    /** @param leadDays 공급시기 약정. null 이면 약정이 없어 법정 기한이 걸린다 */
    private long insertSku(Integer leadDays) {
        long ownerId = fixture.insertUser(
                "ship-due-owner-" + UUID.randomUUID() + "@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status,
                                             supply_lead_days)
                        values (:sellerId, :userId, '발송 기한 상품', 'on_sale', :leadDays)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .param("leadDays", leadDays)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, :stock from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .param("stock", STOCK)
                .query(Long.class)
                .single();
    }
}
