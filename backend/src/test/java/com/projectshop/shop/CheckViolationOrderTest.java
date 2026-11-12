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
 * 주문 묶음의 회계 밖 {@code check} 를 하나씩 부순다(`Q426b`). <b>사례마다 그 제약 하나만 어기는 쓰기를 내고 이름으로 거부를 단언한다.</b>
 *
 * <p>{@code CheckCoverageTest} 의 {@code COVERED_ORDER} 가 이 파일을 부수는 시험으로 든다 — 제약을 지우거나 헐겁게 하면
 * 그 사례가 빨갛다. 정상 행은 배송 완료 묶음 하나와 접수된 반품 하나다({@code ReturnRequestSchemaTest} 와 같은 꼴).
 *
 * <p>한 쓰기가 여러 제약을 어기면 Postgres 는 이름 알파벳 순으로 앞의 것을 낸다 — 사례는 다른 칸을 맞춰 그 제약 하나만 어긴다.
 * 시험 트랜잭션 안에서 쓴다. 지연 트리거는 커밋 때 돌아 여기서 안 깨어난다.
 */
@DisplayName("주문 묶음 check 위반")
class CheckViolationOrderTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private Map<String, Object> ids;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long buyerId = fixture.insertUser("q426b-buyer@test.local", "산사람");
        long staffId = fixture.insertUser("q426b-staff@test.local", "검수자");
        long sellerId = fixture.insertSeller("s-q426b", "위반셀러");

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
                        values (:sellerId, :userId, '위반 시험 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();
        long skuId = jdbc.sql("insert into sku (product_id, price_incl_vat) values (:productId, 0) returning sku_id")
                .param("productId", productId)
                .query(Long.class)
                .single();

        long itemId = jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:bundleId, :skuId, '위반 시험 상품', 0, 1, 0, 0, 0)
                        returning order_item_id
                        """)
                .param("bundleId", bundleId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        long returnId = jdbc.sql("""
                        insert into return_request (seller_order_id, status, reason_code, requested_by_user_id)
                        values (:bundleId, 'requested', 'defect', :userId)
                        returning return_request_id
                        """)
                .param("bundleId", bundleId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        ids = Map.of("order", orderId, "bundle", bundleId, "item", itemId, "sku", skuId,
                "ret", returnId, "user", buyerId, "staff", staffId);
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
                Arguments.of("shop_order_commission_total_check",
                        "update shop_order set commission_total = -1 where order_id = :order"),
                Arguments.of("shop_order_discount_total_check",
                        "update shop_order set discount_total = -1, payable_amount = 1 where order_id = :order"),
                Arguments.of("shop_order_payable_amount_check",
                        "update shop_order set payable_amount = 5 where order_id = :order"),
                Arguments.of("shop_order_shipping_fee_total_check",
                        "update shop_order set shipping_fee_total = -1, payable_amount = -1 where order_id = :order"),
                Arguments.of("shop_order_total_amount_check",
                        "update shop_order set total_amount = -1, payable_amount = -1 where order_id = :order"),
                Arguments.of("seller_order_agreed_lead_days_check",
                        "update seller_order set agreed_lead_days = 61, supply_lead_days = 60 where seller_order_id = :bundle"),
                Arguments.of("seller_order_delivered_dates_check",
                        "update seller_order set delivered_at = null, auto_confirm_at = now() where seller_order_id = :bundle"),
                Arguments.of("seller_order_lead_days_consistent_check",
                        "update seller_order set agreed_lead_days = 5, supply_lead_days = 3 where seller_order_id = :bundle"),
                Arguments.of("seller_order_number_format_check",
                        "update seller_order set seller_order_number = 'S-bad' where seller_order_id = :bundle"),
                Arguments.of("seller_order_shipping_fee_check",
                        "update seller_order set shipping_fee = -1 where seller_order_id = :bundle"),
                Arguments.of("seller_order_supply_lead_days_check",
                        "update seller_order set supply_lead_days = 61 where seller_order_id = :bundle"),
                Arguments.of("order_item_agreement_without_reason_check",
                        "update order_item set withdrawal_notice_agreed_at = now() where order_item_id = :item"),
                Arguments.of("order_item_commission_bp_check",
                        "update order_item set commission_bp = 10001 where order_item_id = :item"),
                Arguments.of("order_item_line_amount_check",
                        "update order_item set line_amount = 5 where order_item_id = :item"),
                Arguments.of("order_item_made_to_order_agreement_check",
                        "update order_item set withdrawal_restriction_reason = 'made_to_order' where order_item_id = :item"),
                Arguments.of("order_item_quantity_check",
                        "update order_item set quantity = 0 where order_item_id = :item"),
                Arguments.of("order_shipping_receiver_phone_check", """
                        insert into order_shipping (order_id, receiver_name, receiver_phone, postal_code, address1)
                        values (:order, '받는이', 'abc', '12345', '주소')
                        """),
                Arguments.of("order_status_history_moved_check", """
                        insert into order_status_history (seller_order_id, from_status, to_status, actor_type)
                        values (:bundle, 'delivered', 'delivered', 'system')
                        """),
                Arguments.of("order_status_history_target_check", """
                        insert into order_status_history (order_id, seller_order_id, from_status, to_status, actor_type)
                        values (:order, :bundle, 'payment_pending', 'paid', 'system')
                        """),
                Arguments.of("order_contract_document_target_check",
                        "insert into order_contract_document (order_id, clause) values (:order, 'terms')"),
                Arguments.of("return_request_bearer_decided_check",
                        "update return_request set return_shipping_fee_bearer = 'consumer' where return_request_id = :ret"),
                Arguments.of("return_request_decided_by_check",
                        "update return_request set decided_by_user_id = :staff where return_request_id = :ret"),
                Arguments.of("return_request_decision_check", """
                        update return_request set decided_at = now(), decided_by_user_id = :staff
                         where return_request_id = :ret
                        """),
                Arguments.of("return_request_defect_bearer_check", """
                        update return_request
                           set status = 'approved', received_at = now(), decided_at = now(),
                               decided_by_user_id = :staff, return_shipping_fee_bearer = 'consumer', restock = true
                         where return_request_id = :ret
                        """),
                Arguments.of("return_request_future_timestamps_check",
                        "update return_request set picked_up_at = now() where return_request_id = :ret"),
                Arguments.of("return_request_inspected_by_check",
                        "update return_request set inspected_by_user_id = :staff where return_request_id = :ret"),
                Arguments.of("return_request_rejected_bearer_check", """
                        update return_request
                           set status = 'rejected', decided_at = now(), decided_by_user_id = :staff,
                               return_shipping_fee_bearer = 'seller', rejection_reason_code = 'other'
                         where return_request_id = :ret
                        """),
                Arguments.of("return_request_rejection_code_presence_check",
                        "update return_request set rejection_reason_code = 'other' where return_request_id = :ret"),
                Arguments.of("return_request_restock_check",
                        "update return_request set restock = true where return_request_id = :ret"),
                Arguments.of("return_request_timeline_check",
                        "update return_request set status = 'picked_up' where return_request_id = :ret"),
                Arguments.of("return_request_item_quantity_check", """
                        insert into return_request_item (return_request_id, order_item_id, quantity)
                        values (:ret, :item, 0)
                        """),
                Arguments.of("return_note_any_check",
                        "insert into return_note (return_request_id) values (:ret)"),
                Arguments.of("return_pickup_postal_code_check", """
                        insert into return_pickup (return_request_id, sender_name, sender_phone, postal_code, address1)
                        values (:ret, '보낸이', '010-1234-5678', 'abc', '주소')
                        """),
                Arguments.of("return_pickup_sender_phone_check", """
                        insert into return_pickup (return_request_id, sender_name, sender_phone, postal_code, address1)
                        values (:ret, '보낸이', 'abc', '12345', '주소')
                        """),
                Arguments.of("cart_owner_check",
                        "insert into cart (user_id, cart_token) values (null, null)"),
                Arguments.of("cart_item_quantity_check", """
                        with new_cart as (insert into cart (user_id) values (:user) returning cart_id)
                        insert into cart_item (cart_id, sku_id, quantity) select cart_id, :sku, 0 from new_cart
                        """));
    }
}
