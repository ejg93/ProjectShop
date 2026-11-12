package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 돈 묶음의 회계 밖 {@code check} 를 하나씩 부순다(`Q426c`). <b>사례마다 그 제약 하나만 어기는 쓰기를 내고 이름으로 거부를 단언한다.</b>
 *
 * <p>{@code CheckCoverageTest} 의 {@code COVERED_MONEY} 가 이 파일을 부수는 시험으로 든다. 정상 행은 배송 완료 묶음 하나 ·
 * 접수된 환불과 그 항목 · 정산 주기와 대기 중 정산 하나다. 결제·쿠폰·정산 항목·일 매출은 사례가 직접 넣는다.
 *
 * <p>한 쓰기가 여러 제약을 어기면 Postgres 는 이름 알파벳 순으로 앞의 것을 낸다 — 사례는 다른 칸을 맞춰 그 제약 하나만 어긴다.
 */
@DisplayName("돈 묶음 check 위반")
class CheckViolationMoneyTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private Map<String, Object> ids;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long buyerId = fixture.insertUser("q426c-buyer@test.local", "산사람");
        long staffId = fixture.insertUser("q426c-staff@test.local", "운영자");
        long sellerId = fixture.insertSeller("s-q426c", "돈셀러");

        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyerId)
                .query(Long.class)
                .single();
        OrderFixture.attachContractDocuments(jdbc, orderId);

        long bundleId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, status)
                        values (:number, :orderId, :sellerId, 'delivered')
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '돈 시험 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();
        long skuId = jdbc.sql("insert into sku (product_id, price_incl_vat) values (:productId, 1000) returning sku_id")
                .param("productId", productId)
                .query(Long.class)
                .single();

        long itemId = jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:bundleId, :skuId, '돈 시험 상품', 1000, 1, 1000, 0, 0)
                        returning order_item_id
                        """)
                .param("bundleId", bundleId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        long refundId = jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, reason_code, amount, due_at,
                                            requested_by_type, requested_by_user_id)
                        values (:number, :bundleId, 'withdrawal', 1000, now() + interval '3 days',
                                'customer', :userId)
                        returning refund_id
                        """)
                .param("number", "R-" + OrderFixture.sellerOrderNumber().substring(2))
                .param("bundleId", bundleId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        long refundItemId = jdbc.sql("""
                        insert into refund_item (refund_id, order_item_id, quantity, amount,
                                                 commission_refund, discount_refund)
                        values (:refundId, :itemId, 1, 1000, 0, 0)
                        returning refund_item_id
                        """)
                .param("refundId", refundId)
                .param("itemId", itemId)
                .query(Long.class)
                .single();

        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (date '2026-01-01', date '2026-01-31', date '2026-02-10')
                        returning settlement_cycle_id
                        """)
                .query(Long.class)
                .single();

        long settlementId = jdbc.sql("""
                        insert into settlement (settlement_cycle_id, seller_id, payout_amount, settlement_number)
                        values (:cycleId, :sellerId, 0, :number)
                        returning settlement_id
                        """)
                .param("cycleId", cycleId)
                .param("sellerId", sellerId)
                .param("number", "T-" + OrderFixture.sellerOrderNumber().substring(2))
                .query(Long.class)
                .single();

        ids = Map.ofEntries(
                Map.entry("order", orderId), Map.entry("bundle", bundleId), Map.entry("item", itemId),
                Map.entry("refund", refundId), Map.entry("refundItem", refundItemId),
                Map.entry("cycle", cycleId), Map.entry("settlement", settlementId),
                Map.entry("seller", sellerId), Map.entry("user", buyerId), Map.entry("staff", staffId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @DisplayName("제약 하나만 어기는 쓰기는 그 이름으로 거부된다")
    void rejects(String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).params(ids).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("payment_amount_check", """
                        insert into payment (order_id, status, method, amount, approval_number)
                        values (:order, 'approved', 'card', 0, 'A-1')
                        """),
                Arguments.of("payment_approval_number_check",
                        "insert into payment (order_id, status, method, amount) values (:order, 'approved', 'card', 1000)"),
                Arguments.of("payment_decline_reason_check",
                        "insert into payment (order_id, status, method, amount) values (:order, 'failed', 'card', 1000)"),
                Arguments.of("payment_card_last4_format_check", """
                        with new_payment as (
                            insert into payment (order_id, status, method, amount, approval_number)
                            values (:order, 'approved', 'card', 1000, 'A-1')
                            returning payment_id
                        )
                        insert into payment_card (payment_id, card_issuer, card_last4)
                        select payment_id, '국민', '12a4' from new_payment
                        """),
                Arguments.of("refund_amount_check",
                        "update refund set amount = 0 where refund_id = :refund"),
                Arguments.of("refund_delay_interest_check",
                        "update refund set delay_interest = -1 where refund_id = :refund"),
                Arguments.of("refund_number_format_check",
                        "update refund set refund_number = 'R-bad' where refund_id = :refund"),
                Arguments.of("refund_requested_by_user_check",
                        "update refund set requested_by_type = 'system' where refund_id = :refund"),
                Arguments.of("refund_shipping_fee_refund_check",
                        "update refund set shipping_fee_refund = -1 where refund_id = :refund"),
                Arguments.of("refund_item_amount_check",
                        "update refund_item set amount = 0 where refund_item_id = :refundItem"),
                Arguments.of("refund_item_commission_refund_check",
                        "update refund_item set commission_refund = -1 where refund_item_id = :refundItem"),
                Arguments.of("refund_item_discount_refund_check",
                        "update refund_item set discount_refund = -1 where refund_item_id = :refundItem"),
                Arguments.of("refund_item_quantity_check",
                        "update refund_item set quantity = 0 where refund_item_id = :refundItem"),
                Arguments.of("refund_note_any_check",
                        "insert into refund_note (refund_id) values (:refund)"),
                Arguments.of("settlement_carried_over_check",
                        "update settlement set carried_over = 5 where settlement_id = :settlement"),
                Arguments.of("settlement_number_format_check",
                        "update settlement set settlement_number = 'T-bad' where settlement_id = :settlement"),
                Arguments.of("settlement_payout_amount_check", """
                        update settlement
                           set payout_status = 'requested', payout_requested_at = now(),
                               payout_requested_by_user_id = :staff
                         where settlement_id = :settlement
                        """),
                Arguments.of("settlement_payout_decision_check",
                        "update settlement set payout_decided_at = now() where settlement_id = :settlement"),
                Arguments.of("settlement_payout_request_check",
                        "update settlement set payout_requested_at = now() where settlement_id = :settlement"),
                Arguments.of("settlement_cycle_payout_date_check",
                        "update settlement_cycle set payout_date = period_end where settlement_cycle_id = :cycle"),
                Arguments.of("settlement_cycle_period_check",
                        "update settlement_cycle set period_end = period_start - 1 where settlement_cycle_id = :cycle"),
                Arguments.of("settlement_item_amount_sign_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id)
                        values (:settlement, 'sale', -1, :item)
                        """),
                Arguments.of("settlement_item_commission_amount_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id,
                                                     commission_bp, commission_base_amount)
                        values (:settlement, 'commission', -10, :item, 1000, 1000)
                        """),
                Arguments.of("settlement_item_commission_base_amount_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id, commission_base_amount)
                        values (:settlement, 'sale', 1000, :item, -1)
                        """),
                Arguments.of("settlement_item_commission_basis_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id,
                                                     commission_bp, commission_base_amount)
                        values (:settlement, 'sale', 1000, :item, 100, 1000)
                        """),
                Arguments.of("settlement_item_commission_bp_range_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id,
                                                     commission_bp, commission_base_amount)
                        values (:settlement, 'commission', -1000, :item, 10001, 1000)
                        """),
                Arguments.of("settlement_item_compensation_bearer_check", """
                        insert into settlement_item (settlement_id, kind, amount, order_item_id, compensation_bearer)
                        values (:settlement, 'sale', 1000, :item, 'seller')
                        """),
                Arguments.of("settlement_item_source_check", """
                        insert into settlement_item (settlement_id, kind, amount, seller_order_id)
                        values (:settlement, 'sale', 1000, :bundle)
                        """),
                Arguments.of("coupon_bearer_seller_check", """
                        insert into coupon (code, name, discount_kind, discount_value, bearer)
                        values ('Q426C', '위반 쿠폰', 'amount', 1000, 'seller')
                        """),
                Arguments.of("coupon_max_discount_check", """
                        insert into coupon (code, name, discount_kind, discount_value, bearer, max_discount_amount)
                        values ('Q426C', '위반 쿠폰', 'amount', 1000, 'mall', 500)
                        """),
                Arguments.of("coupon_min_order_amount_check", """
                        insert into coupon (code, name, discount_kind, discount_value, bearer, min_order_amount)
                        values ('Q426C', '위반 쿠폰', 'amount', 1000, 'mall', -1)
                        """),
                Arguments.of("coupon_issue_expiry_after_issued_check", """
                        with new_coupon as (
                            insert into coupon (code, name, discount_kind, discount_value, bearer)
                            values ('Q426C', '위반 쿠폰', 'amount', 1000, 'mall')
                            returning coupon_id
                        )
                        insert into coupon_issue (coupon_id, user_id, expires_at)
                        select coupon_id, :user, now() - interval '1 day' from new_coupon
                        """),
                Arguments.of("seller_daily_sales_amounts_check", """
                        insert into seller_daily_sales (seller_id, sales_date, order_count, sold_quantity,
                                                        paid_amount, refund_count, refunded_amount)
                        values (:seller, date '2026-01-02', 1, 1, -1, 0, 0)
                        """),
                Arguments.of("seller_daily_sales_counts_check", """
                        insert into seller_daily_sales (seller_id, sales_date, order_count, sold_quantity,
                                                        paid_amount, refund_count, refunded_amount)
                        values (:seller, date '2026-01-02', 1, -1, 0, 0, 0)
                        """),
                Arguments.of("seller_daily_sales_mall_discount_check", """
                        insert into seller_daily_sales (seller_id, sales_date, order_count, sold_quantity,
                                                        paid_amount, refund_count, refunded_amount,
                                                        mall_discount_amount)
                        values (:seller, date '2026-01-02', 1, 1, 0, 0, 0, -1)
                        """),
                Arguments.of("seller_daily_sales_not_empty_check", """
                        insert into seller_daily_sales (seller_id, sales_date, order_count, sold_quantity,
                                                        paid_amount, refund_count, refunded_amount)
                        values (:seller, date '2026-01-02', 0, 0, 0, 0, 0)
                        """));
    }
}
