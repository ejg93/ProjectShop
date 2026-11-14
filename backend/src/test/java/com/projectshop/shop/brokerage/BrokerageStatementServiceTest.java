package com.projectshop.shop.brokerage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;
import com.projectshop.shop.payment.RefundService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 분기 중개 명세가 셀러·승인 월·결제수단마다 건수와 셀러 공급 총액을 세는지 잰다(`Q455`, 부가가치세법 제75조 `D2` R46).
 *
 * <p><b>금액은 셀러 공급 총액이다</b>(2026-11-13 사용자 결정) — 줄 금액 + 배송비 − 셀러 부담 쿠폰. 몰 부담 쿠폰은 셀러 공급가를
 * 안 줄이므로 안 뺀다. 환불은 안 뺀다(서식에 칸이 없다). 결제·환불은 서비스로 만들고 결제 시각만 SQL 로 지난 분기에 옮긴다.
 */
@DisplayName("중개 명세")
class BrokerageStatementServiceTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final String GOOD_CARD = "4242-4242-4242-4242";
    private static final String DECLINED_CARD = "4242-4242-4242-0000";

    @Autowired
    private BrokerageStatementService statements;

    @Autowired
    private OrderService orders;

    @Autowired
    private PaymentService payments;

    @Autowired
    private RefundService refunds;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long adminId;
    private long sellerA;
    private long sellerB;
    private LocalDate today;
    private LocalDate lastQuarterStart;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        buyerId = fixture.insertUser("brokerage-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");
        adminId = fixture.insertUser("brokerage-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");
        sellerA = seller("s-brokerage-a");
        sellerB = seller("s-brokerage-b");
        today = LocalDate.now(BusinessCalendar.ZONE);
        LocalDate thisQuarterStart = today.withDayOfMonth(1).withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1);
        lastQuarterStart = thisQuarterStart.minusMonths(3);
    }

    @Test
    @DisplayName("셀러·월·수단마다 한 줄이고 금액은 줄 금액 + 배송비 − 셀러 부담 할인이다")
    void talliesSupplyTotals() {
        long withMallCoupon = order(sellerA, 3, PaymentMethod.CARD, coupon("mall", null));
        long withSellerCoupon = order(sellerB, 1, PaymentMethod.CARD, coupon("seller", sellerB));
        long transfer = order(sellerA, 1, PaymentMethod.TRANSFER, null);
        moveToLastQuarter(withMallCoupon, withSellerCoupon, transfer);

        assertThat(statements.closeMissing(today)).isEqualTo(1);

        assertThat(lines()).containsExactlyInAnyOrder(
                List.of(sellerA, "card", 1L, 3 * PRICE + shippingOf(withMallCoupon)),
                List.of(sellerB, "card", 1L, PRICE - 1_000 + shippingOf(withSellerCoupon)),
                List.of(sellerA, "transfer", 1L, PRICE + shippingOf(transfer)));
    }

    @Test
    @DisplayName("거절된 결제와 이번 분기 결제는 안 든다")
    void skipsDeclinedAndCurrentQuarter() {
        long declined = order(sellerA, 1, null, null);
        long current = order(sellerB, 1, PaymentMethod.CARD, null);
        moveToLastQuarter(declined);
        jdbc.sql("""
                        update payment set created_at = cast(:day as date) at time zone 'Asia/Seoul'
                         where order_id = :orderId
                        """)
                .param("day", lastQuarterStart.plusMonths(3))
                .param("orderId", current)
                .update();

        statements.closeMissing(today);

        assertThat(lines())
                .as("분기 첫날 00:00 KST 결제는 다음 분기다 — 거절된 결제는 공급이 아니다")
                .isEmpty();
    }

    @Test
    @DisplayName("환불해도 줄이 그대로고 두 번 불러도 같다")
    void ignoresRefundsAndIsIdempotent() {
        long paid = order(sellerA, 1, PaymentMethod.CARD, null);
        moveToLastQuarter(paid);
        refundWholeBundle(paid);

        assertThat(statements.closeMissing(today)).isEqualTo(1);
        assertThat(statements.closeMissing(today)).isZero();

        assertThat(lines()).containsExactly(List.of(sellerA, "card", 1L, PRICE + shippingOf(paid)));
    }

    @Test
    @DisplayName("기한 다음 날 미제출이면 연체로 세고 제출 기록을 넣으면 안 센다")
    void countsOverdue() {
        statements.closeMissing(today);
        LocalDate due = jdbc.sql("select due_date from brokerage_statement").query(LocalDate.class).single();

        assertThat(due)
                .as("분기 말일의 다음 달 15일 — 부가가치세법 시행령 제121조제1항")
                .isEqualTo(lastQuarterStart.plusMonths(3).withDayOfMonth(15));
        assertThat(statements.countOverdue(due)).isZero();
        assertThat(statements.countOverdue(due.plusDays(1))).isEqualTo(1);

        jdbc.sql("""
                        insert into brokerage_statement_submission (brokerage_statement_id, submitted_by_user_id, file_sha256)
                        select brokerage_statement_id, :admin, repeat('a', 64) from brokerage_statement
                        """)
                .param("admin", adminId)
                .update();
        assertThat(statements.countOverdue(due.plusDays(1))).isZero();
    }

    @Test
    @DisplayName("분기 번호는 1~4 다")
    void rejectsQuarterFive() {
        violates("brokerage_statement_period_quarter_check", """
                insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                values (2026, 5, date '2026-01-01', date '2026-03-31', date '2026-04-15')
                """);
    }

    @Test
    @DisplayName("기한은 분기 끝 뒤다")
    void rejectsDueBeforeEnd() {
        violates("brokerage_statement_period_check", """
                insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                values (2026, 1, date '2026-01-01', date '2026-03-31', date '2026-03-31')
                """);
    }

    @Test
    @DisplayName("같은 분기는 한 번만 선다")
    void oneStatementPerQuarter() {
        statement();
        violates("brokerage_statement_period_unique", """
                insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                values (2020, 1, date '2020-01-01', date '2020-03-31', date '2020-04-15')
                """);
    }

    @Test
    @DisplayName("줄의 결제수단은 우리 값 둘이다")
    void rejectsUnknownMethod() {
        violates("brokerage_statement_line_method_check", line(statement(), "2020-01-01", "cash", 1, 1));
    }

    @Test
    @DisplayName("줄의 건수는 1 이상이다")
    void rejectsZeroCount() {
        violates("brokerage_statement_line_sale_count_check", line(statement(), "2020-01-01", "card", 0, 1));
    }

    @Test
    @DisplayName("줄의 금액은 음수가 아니다")
    void rejectsNegativeAmount() {
        violates("brokerage_statement_line_sale_amount_check", line(statement(), "2020-01-01", "card", 1, -1));
    }

    @Test
    @DisplayName("줄의 월은 그달 1일이다")
    void rejectsMidMonth() {
        violates("brokerage_statement_line_sales_month_check", line(statement(), "2020-01-15", "card", 1, 1));
    }

    @Test
    @DisplayName("제출 기록의 해시는 소문자 16진 64자다")
    void rejectsBadHash() {
        violates("brokerage_statement_submission_file_sha256_check", """
                insert into brokerage_statement_submission (brokerage_statement_id, submitted_by_user_id, file_sha256)
                values (%d, %d, 'ABC')
                """.formatted(statement(), adminId));
    }

    /** 2020년 1분기 머리 행 */
    private long statement() {
        return jdbc.sql("""
                        insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                        values (2020, 1, date '2020-01-01', date '2020-03-31', date '2020-04-15')
                        returning brokerage_statement_id
                        """)
                .query(Long.class)
                .single();
    }

    private String line(long statement, String month, String method, int count, long amount) {
        return """
                insert into brokerage_statement_line (brokerage_statement_id, seller_id, sales_month, method, sale_count, sale_amount)
                values (%d, %d, date '%s', '%s', %d, %d)
                """.formatted(statement, sellerA, month, method, count, amount);
    }

    private void violates(String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    /** (셀러, 수단, 건수, 금액) 줄 */
    private List<List<Object>> lines() {
        return jdbc.sql("""
                        select seller_id, method, sale_count, sale_amount
                          from brokerage_statement_line
                         where seller_id in (:a, :b)
                        """)
                .param("a", sellerA)
                .param("b", sellerB)
                .query((rs, rowNum) -> List.<Object>of(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    private long shippingOf(long orderId) {
        return jdbc.sql("select shipping_fee from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    /** 지난 분기 둘째 날 정오(KST)로 결제 시각을 옮긴다 */
    private void moveToLastQuarter(long... orderIds) {
        for (long orderId : orderIds) {
            jdbc.sql("""
                            update payment
                               set created_at = (cast(:day as date) + interval '12 hours') at time zone 'Asia/Seoul'
                             where order_id = :orderId
                            """)
                    .param("day", lastQuarterStart.plusDays(1))
                    .param("orderId", orderId)
                    .update();
        }
    }

    /**
     * 그 셀러의 상품을 {@code items} 가지 담아 주문하고 낸다. {@code method} 가 null 이면 거절되는 카드다.
     *
     * @return 주문 내부 키
     */
    private long order(long sellerId, int items, PaymentMethod method, Long couponIssueId) {
        long ownerId = jdbc.sql("select user_id from seller_member where seller_id = :sellerId limit 1")
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyerId)
                        .query(Long.class)
                        .single());
        List<Long> cartItems = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) {
            cartItems.add(jdbc.sql("""
                            insert into cart_item (cart_id, sku_id, quantity)
                            values (:cartId, :skuId, 1)
                            returning cart_item_id
                            """)
                    .param("cartId", cartId)
                    .param("skuId", insertSku(sellerId, ownerId))
                    .query(Long.class)
                    .single());
        }
        OrderService.Created created = orders.create(buyerId, new OrderService.Command(cartItems,
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null),
                false, couponIssueId));
        PaymentMethod paidWith = method == null ? PaymentMethod.CARD : method;
        String card = method == null ? DECLINED_CARD : method == PaymentMethod.CARD ? GOOD_CARD : null;
        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), paidWith, card));
        return created.orderId();
    }

    /** 1,000원 정액 쿠폰을 발급한다 */
    private long coupon(String bearer, Long sellerId) {
        long couponId = jdbc.sql("""
                        insert into coupon (code, name, discount_kind, discount_value, bearer, seller_id)
                        values (:code, :code, 'amount', 1000, :bearer, :seller)
                        returning coupon_id
                        """)
                .param("code", "BRK-" + bearer.toUpperCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8))
                .param("bearer", bearer)
                .param("seller", sellerId)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                        insert into coupon_issue (coupon_id, user_id, expires_at)
                        values (:coupon, :user, now() + interval '30 days')
                        returning coupon_issue_id
                        """)
                .param("coupon", couponId)
                .param("user", buyerId)
                .query(Long.class)
                .single();
    }

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

    private long seller(String code) {
        long sellerId = fixture.insertSeller(code, code);
        fixture.verifySeller(sellerId);
        long ownerId = fixture.insertUser(code + "@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);
        return sellerId;
    }

    private long insertSku(long sellerId, long ownerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '명세 상품', 'on_sale')
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
