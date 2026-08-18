package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 멱등이 걸려야 하는 경로가 <b>실제로 걸려 있나</b>.
 *
 * <p>이 테스트가 이 프로젝트에서 멱등 커버리지를 지키는 유일한 장치다.
 * 필터로 헤더를 강제하는 방법도 있었지만, 필터는 {@code IdempotencyService.run()} 이 실제로 불렸는지
 * 모르므로 <b>"헤더는 요구하는데 멱등은 안 걸린" 상태</b>를 만들 수 있고 그건 아예 없는 것보다 나쁘다.
 * 목록이 여기 한 곳에 살면서 요구가 아니라 확인을 한다(`D11`).
 *
 * <p><b>새 경로를 만들면 {@link #IDEMPOTENT_PATHS} 에 넣는다.</b> 청크 12 의 결제가 다음 차례다.
 */
@AutoConfigureMockMvc
@DisplayName("멱등 커버리지")
class OrderIdempotencyTest extends PostgresTestBase {

    /** `D11` 이 멱등키를 필수로 정한 경로. 돈이나 재고가 움직이는 POST 만 여기 든다 */
    static final List<String> IDEMPOTENT_PATHS = List.of("/api/orders", "/api/payments");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private ShopUser buyer;
    private long cartItemId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        long sellerId = fixture.insertSeller("idem-seller", "멱등셀러");
        fixture.verifySeller(sellerId);

        long buyerId = fixture.insertUser("idem-buyer@test.local", "구매자");
        buyer = new ShopUser(buyerId, "idem-buyer@test.local", null, true);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '멱등 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        long skuId = jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count) values (:productId, 10000, 50)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();

        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity) values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();
    }

    @ParameterizedTest
    @FieldSource("IDEMPOTENT_PATHS")
    @DisplayName("멱등키 없이 부르면 400 이다")
    void requiresIdempotencyKey(String path) throws Exception {
        mvc.perform(post(path).with(user(buyer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFor(path)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 그 경로가 받는 본문. <b>경로마다 달라야 이 테스트가 뜻을 갖는다</b> —
     * 아무 본문이나 보내면 400 이 나긴 하는데 그건 형식이 틀려서지 키가 없어서가 아니다.
     *
     * <p>결제 본문의 주문번호는 실재하지 않는 것이다. 헤더가 없으면 컨트롤러 본체가 아예 안 돌아서
     * 주문을 찾는 자리까지 못 간다 — 그 사실이 여기서 보려는 것과 같다.
     */
    private String bodyFor(String path) {
        return "/api/payments".equals(path)
                ? """
                {"order_number": "20260101-2222AA", "method": "card",
                 "card_number": "4242424242424242"}
                """
                : body();
    }

    @Test
    @DisplayName("같은 키로 두 번 보내면 주문이 하나만 생긴다")
    void createsOnlyOneOrder() throws Exception {
        String key = "11111111-2222-3333-4444-555555555555";

        mvc.perform(orderRequest(key)).andExpect(status().isCreated());
        mvc.perform(orderRequest(key)).andExpect(status().isCreated());

        assertThat(orderCount())
                .as("감싸는 것을 빠뜨리면 재전송이 주문을 하나 더 만들고 재고가 두 번 빠진다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("재고도 한 번만 빠진다")
    void decreasesStockOnlyOnce() throws Exception {
        String key = "66666666-7777-8888-9999-000000000000";

        mvc.perform(orderRequest(key)).andExpect(status().isCreated());
        mvc.perform(orderRequest(key)).andExpect(status().isCreated());

        assertThat(jdbc.sql("select stock_count from sku limit 1").query(Integer.class).single())
                .isEqualTo(49);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder orderRequest(String key) {
        return post("/api/orders").with(user(buyer)).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body());
    }

    private String body() {
        return """
                {"cart_item_ids": [%d],
                 "shipping": {"receiver_name": "홍길동", "receiver_phone": "010-0000-0000",
                              "postal_code": "06134", "address1": "서울시 강남구"}}
                """.formatted(cartItemId);
    }

    private int orderCount() {
        return jdbc.sql("select count(*) from shop_order").query(Integer.class).single();
    }
}
