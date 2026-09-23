package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;

/**
 * 송장과 함께 발송한다(`57`).
 *
 * <p><b>강제 지점이 둘이다</b> — 입구가 송장을 필수로 받고(없으면 400), DB 가 형식과 「보내기 전엔 송장 없음」을 막는다.
 * 위치 칸은 없다 — 없는 것을 시험할 수는 없어서 {@code V104} 의 주석이 그 결정을 든다.
 */
@DisplayName("송장과 함께 발송")
class ShipmentTrackingTest extends PostgresTestBase {

    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderService orders;

    @Autowired
    private PaymentService payments;

    @Autowired
    private JdbcClient jdbc;

    private ShopUser buyer;
    private ShopUser owner;
    private String orderNumber;
    private String sellerOrderNumber;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        long buyerId = fixture.insertUser("tracking-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");
        buyer = new ShopUser(buyerId, "tracking-buyer@test.local", null, true);

        long sellerId = fixture.insertSeller("s-tracking", "송장셀러");
        fixture.verifySeller(sellerId);
        long ownerId = fixture.insertUser("tracking-owner@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);
        owner = new ShopUser(ownerId, "tracking-owner@test.local", null, true);

        long skuId = insertSku(sellerId, ownerId);
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyerId)
                .query(Long.class)
                .single();
        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity) values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        OrderService.Created created = orders.create(buyerId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)));
        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), PaymentMethod.CARD, GOOD_CARD));
        orderNumber = created.orderNumber();
        sellerOrderNumber = jdbc.sql("select seller_order_number from seller_order where order_id = :id")
                .param("id", created.orderId())
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("보내면 택배사와 숫자만 남긴 송장이 묶음에 남고 사는 사람에게 보인다")
    void storesTrackingWithTheShipment() throws Exception {
        ship(owner, """
                {"carrier_code": "HANJIN", "tracking_no": "1234-5678-9012"}
                """).andExpect(status().isNoContent());

        mvc.perform(get("/api/orders/{number}", orderNumber).with(user(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller_orders[0].status").value("SHIPPING"))
                .andExpect(jsonPath("$.seller_orders[0].carrier_code").value("HANJIN"))
                .andExpect(jsonPath("$.seller_orders[0].tracking_no").value("123456789012"));
    }

    @Test
    @DisplayName("송장이 없거나 모르는 택배사면 400 이고 발송도 안 된다")
    void refusesShipmentWithoutTracking() throws Exception {
        ship(owner, "{}").andExpect(status().isBadRequest());
        ship(owner, """
                {"carrier_code": "DRONE", "tracking_no": "123456789012"}
                """).andExpect(status().isBadRequest());
        ship(owner, """
                {"carrier_code": "CJ", "tracking_no": "12345"}
                """).andExpect(status().isBadRequest());

        assertThat(statusOf()).isEqualTo("preparing");
    }

    /** 판정이 먼저다 — 발송할 수 없는 사람이 보낸 송장은 묶음에 안 닿는다 */
    @Test
    @DisplayName("셀러가 아니면 발송도 송장도 안 된다")
    void leavesNoTrackingWhenTheTransitionFails() throws Exception {
        ship(buyer, """
                {"carrier_code": "CJ", "tracking_no": "123456789012"}
                """).andExpect(status().is4xxClientError());

        assertThat(jdbc.sql("select tracking_no from seller_order where seller_order_number = :n")
                .param("n", sellerOrderNumber)
                .query(String.class)
                .optional())
                .isEmpty();
    }

    @Test
    @DisplayName("DB 가 송장 형식을 막는다")
    void rejectsMalformedTrackingInTheDatabase() {
        markShipped();

        assertThatThrownBy(() -> setTracking("cj", "12-34"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seller_order_tracking_no_check");
    }

    @Test
    @DisplayName("DB 가 택배사 없는 송장을 막는다")
    void rejectsTrackingWithoutCarrier() {
        markShipped();

        assertThatThrownBy(() -> setTracking(null, "123456789012"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seller_order_tracking_pair_check");
    }

    private void markShipped() {
        jdbc.sql("update seller_order set shipped_at = now() where seller_order_number = :n")
                .param("n", sellerOrderNumber)
                .update();
    }

    @Test
    @DisplayName("보내기 전에는 송장을 못 붙인다")
    void rejectsTrackingBeforeShipment() {
        assertThatThrownBy(() -> setTracking("cj", "123456789012"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seller_order_tracking_after_ship_check");
    }

    private ResultActions ship(ShopUser who, String body) throws Exception {
        return mvc.perform(post("/api/shipments/{number}/ship", sellerOrderNumber)
                .with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void setTracking(String carrier, String trackingNo) {
        jdbc.sql("""
                        update seller_order set carrier_code = :carrier, tracking_no = :tracking
                         where seller_order_number = :n
                        """)
                .param("carrier", carrier)
                .param("tracking", trackingNo)
                .param("n", sellerOrderNumber)
                .update();
    }

    private String statusOf() {
        return jdbc.sql("select status from seller_order where seller_order_number = :n")
                .param("n", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private long insertSku(long sellerId, long ownerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '송장 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat) values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
