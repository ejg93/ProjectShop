package com.projectshop.shop.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;
import com.projectshop.shop.payment.RefundService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 일별 매출 집계(`40`).
 *
 * <p><b>이 파일의 첫 시험이 강제 지점이다</b> — 집계 합과 원장 합을 <b>따로 쓴 식</b>으로 대조한다. 사전 집계는
 * 원장과 갈려도 아무것도 안 깨지고 통계 화면만 조용히 틀린다. 그래서 대조 쪽 식을 {@link DailySalesService} 의
 * 식과 공유하지 않는다 — 같은 식이면 둘이 같이 틀린다.
 *
 * <p>결제·환불은 서비스로 만든다(fixture 가 사실이어야 한다). 날짜만 SQL 로 옮긴다.
 */
@DisplayName("일별 매출 집계")
class DailySalesServiceTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private DailySalesService sales;

    @Autowired
    private OrderService orders;

    @Autowired
    private PaymentService payments;

    @Autowired
    private RefundService refunds;

    @Autowired
    private JdbcClient jdbc;

    private long buyerId;
    private long adminId;
    private long sellerId;
    private long skuId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("daily-sales-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");
        adminId = fixture.insertUser("daily-sales-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");

        sellerId = fixture.insertSeller("s-daily-sales", "집계셀러");
        fixture.verifySeller(sellerId);
        long ownerId = fixture.insertUser("daily-sales-owner@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);

        skuId = insertSku(ownerId);
        today = LocalDate.now(BusinessCalendar.ZONE);
    }

    @Test
    @DisplayName("집계 합이 원장 합과 같다")
    void matchesTheLedger() {
        long first = placeAndPay(2);
        placeAndPay(1);
        refundWholeBundle(first);

        sales.rebuild(today);

        assertThat(row(today)).containsExactly(2L, 3L, 3 * PRICE, 1L, 2 * PRICE);
        assertThat(row(today)).isEqualTo(ledger(today));
    }

    @Test
    @DisplayName("한국 자정으로 자른다 — 어제 23:59 결제는 어제에 든다")
    void cutsAtKoreanMidnight() {
        long order = placeAndPay(1);
        jdbc.sql("""
                        update payment
                           set created_at = (cast(:day as date) - interval '1 second') at time zone 'Asia/Seoul'
                         where order_id = :orderId
                        """)
                .param("day", today)
                .param("orderId", order)
                .update();

        sales.rebuild(today);
        sales.rebuild(today.minusDays(1));

        assertThat(row(today)).isEmpty();
        assertThat(row(today.minusDays(1))).containsExactly(1L, 1L, PRICE, 0L, 0L);
    }

    @Test
    @DisplayName("환불은 결제한 날이 아니라 환불된 날에 든다")
    void putsRefundsOnTheirOwnDay() {
        long order = placeAndPay(1);
        jdbc.sql("update payment set created_at = created_at - interval '1 day' where order_id = :orderId")
                .param("orderId", order)
                .update();
        refundWholeBundle(order);

        sales.rebuild(today.minusDays(1));
        sales.rebuild(today);

        assertThat(row(today.minusDays(1))).containsExactly(1L, 1L, PRICE, 0L, 0L);
        assertThat(row(today)).containsExactly(0L, 0L, 0L, 1L, PRICE);
    }

    @Test
    @DisplayName("두 번 돌아도 결과가 같다")
    void isRepeatable() {
        placeAndPay(2);

        sales.rebuild(today);
        sales.rebuild(today);

        assertThat(row(today)).containsExactly(1L, 2L, 2 * PRICE, 0L, 0L);
    }

    @Test
    @DisplayName("오늘은 표를 안 거치고 원장에서 센다")
    void talliesTodayFromTheLedger() {
        placeAndPay(1);

        List<DailySalesService.Day> days = sales.tally(today, today.plusDays(1)).stream()
                .filter(day -> day.sellerId() == sellerId)
                .toList();

        assertThat(days).singleElement()
                .satisfies(day -> assertThat(day.paidAmount()).isEqualTo(PRICE));
        assertThat(row(today)).isEmpty();
    }

    /** 표의 한 줄. 없으면 빈 목록이다 */
    private List<Long> row(LocalDate day) {
        return jdbc.sql("""
                        select order_count, sold_quantity, paid_amount, refund_count, refunded_amount
                          from seller_daily_sales
                         where seller_id = :sellerId and sales_date = :day
                        """)
                .param("sellerId", sellerId)
                .param("day", day)
                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5)))
                .optional()
                .orElse(List.of());
    }

    /**
     * 같은 날을 원장에서 <b>따로</b> 센다. 결제와 환불을 한 식으로 묶지 않고 둘로 나눠 물어서, 집계 쪽의
     * {@code full join} 이 줄을 겹치거나 빠뜨리면 여기서 갈린다.
     */
    private List<Long> ledger(LocalDate day) {
        List<Long> paid = jdbc.sql("""
                        select count(distinct so.seller_order_id), coalesce(sum(oi.quantity), 0),
                               coalesce(sum(oi.line_amount - oi.discount_amount), 0)
                          from payment p
                          join seller_order so on so.order_id = p.order_id
                          join order_item oi on oi.seller_order_id = so.seller_order_id
                         where p.status = 'approved' and so.seller_id = :sellerId
                           and (p.created_at at time zone 'Asia/Seoul')::date = :day
                        """)
                .param("sellerId", sellerId)
                .param("day", day)
                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
        List<Long> refunded = jdbc.sql("""
                        select count(distinct r.refund_id), coalesce(sum(ri.amount), 0)
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join refund_item ri on ri.refund_id = r.refund_id
                         where r.status = 'approved' and so.seller_id = :sellerId
                           and (r.decided_at at time zone 'Asia/Seoul')::date = :day
                        """)
                .param("sellerId", sellerId)
                .param("day", day)
                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2)))
                .single();
        return Stream.concat(paid.stream(), refunded.stream()).toList();
    }

    /** 주문하고 결제한다. 주문 번호(내부 키)를 돌려준다 */
    private long placeAndPay(int quantity) {
        // 사람마다 장바구니가 하나다(`cart_user_id_key`). 두 번째 주문은 있는 것을 쓴다.
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyerId)
                        .query(Long.class)
                        .single());
        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, :quantity)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .param("quantity", quantity)
                .query(Long.class)
                .single();

        OrderService.Created created = orders.create(buyerId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)));
        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), PaymentMethod.CARD, GOOD_CARD));
        return created.orderId();
    }

    /** 묶음을 취소로 닫고 전부 환불한다. 요청은 고객이, 승인은 관리자가 한다 */
    private void refundWholeBundle(long orderId) {
        String sellerOrderNumber = jdbc.sql("""
                        update seller_order set status = 'cancelled', closed_at = now()
                         where order_id = :orderId
                        returning seller_order_number
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();

        RefundService.Refund refund = refunds.request(buyerId,
                new RefundService.RequestCommand(sellerOrderNumber, "cancelled", List.of(), null));
        refunds.approve(adminId, refund.refundNumber(), null);
    }

    private long insertSku(long ownerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '집계 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 100 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
