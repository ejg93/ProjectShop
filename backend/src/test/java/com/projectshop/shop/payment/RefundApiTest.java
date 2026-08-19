package com.projectshop.shop.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.order.OrderService;

/**
 * 환불 입구의 <b>경로·상태 코드·판정 경계</b>.
 *
 * <p>{@code RefundServiceTest} 는 서비스를 직접 부르므로 경로도 응답 표기도 안 밟는다.
 * 여기서 보는 것은 <b>권한을 둘로 가른 것이 HTTP 에서 실제로 갈리는가</b>(`V24`)와,
 * <b>기한 넘긴 것이 조회로 드러나는가</b>(`D2` R5)다. 뒤쪽은 법 요건의 강제 지점이라
 * 이 테스트가 없으면 「드러난다」를 아무것도 안 지킨다.
 */
@AutoConfigureMockMvc
@DisplayName("환불 API")
class RefundApiTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int ORDERED = 2;
    private static final int STOCK = 10;

    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private ShopUser buyer;
    private ShopUser admin;
    private ShopUser sellerOwner;
    private long skuId;
    private long orderId;
    private String sellerOrderNumber;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        long buyerId = fixture.insertUser("refund-api-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");
        buyer = new ShopUser(buyerId, "refund-api-buyer@test.local", null, true);

        long adminId = fixture.insertUser("refund-api-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");
        admin = new ShopUser(adminId, "refund-api-admin@test.local", null, true);

        long sellerId = fixture.insertSeller("s-refund-api", "환불API셀러");
        fixture.verifySeller(sellerId);

        long ownerId = fixture.insertUser("refund-api-owner@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);
        sellerOwner = new ShopUser(ownerId, "refund-api-owner@test.local", null, true);

        skuId = insertSku(sellerId, ownerId);

        OrderService.Created created = placeOrder(buyerId);
        orderId = created.orderId();

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), "card", GOOD_CARD));

        sellerOrderNumber = jdbc.sql(
                        "select seller_order_number from seller_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();

        closeBundle("cancelled");
    }

    @Nested
    @DisplayName("요청은")
    class Requesting {

        @Test
        @DisplayName("201 과 함께 자기를 가리키는 경로를 준다")
        void answersWithCreated() throws Exception {
            mvc.perform(requestBy(buyer))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.startsWith("/api/refunds/R-")))
                    .andExpect(jsonPath("$.status").value("REQUESTED"))
                    .andExpect(jsonPath("$.amount").value(PRICE * ORDERED + 3000))
                    .andExpect(jsonPath("$.due_at").exists());
        }

        @Test
        @DisplayName("셀러도 낼 수 있다")
        void isOpenToSellers() throws Exception {
            mvc.perform(requestBy(sellerOwner))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("모르는 사유는 형식에서 걸린다")
        void rejectsUnknownReason() throws Exception {
            mvc.perform(post("/api/refunds").with(user(buyer)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"seller_order_number": "%s", "reason_code": "그냥"}
                                    """.formatted(sellerOrderNumber)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("남의 묶음은 없는 것과 같다")
        void hidesOthersBundles() throws Exception {
            long stranger = fixture.insertUser("refund-api-stranger@test.local", "남");
            fixture.grantGlobal(stranger, "customer");

            mvc.perform(requestBy(new ShopUser(stranger, "refund-api-stranger@test.local", null, true)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("승인은")
    class Approving {

        @Test
        @DisplayName("관리자가 하면 PG 거래번호가 찬다")
        void isDoneByAdmin() throws Exception {
            String number = requestAndRead();

            mvc.perform(post("/api/refunds/{number}/approve", number)
                            .with(user(admin)).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.gateway_refund_number").isNotEmpty());
        }

        /**
         * <b>권한을 둘로 가른 값이 여기서 나온다</b>(`V24`).
         *
         * <p>고객은 요청은 내지만 승인은 못 한다. 하나였으면 요청을 열려고 준 권한이
         * 승인까지 열어서 <b>남의 환불을 승인</b>할 수 있었다 —
         * {@code refund_self_approval_check} 는 자기 것만 막는다.
         */
        @Test
        @DisplayName("고객에게는 없는 요청과 같은 404 다")
        void isClosedToCustomers() throws Exception {
            String number = requestAndRead();

            mvc.perform(post("/api/refunds/{number}/approve", number)
                            .with(user(buyer)).with(csrf()))
                    .andExpect(status().isNotFound());
        }

        /**
         * 셀러도 못 한다. <b>근거가 법이다</b>(`D2` R5).
         *
         * <p>제18조제2항 괄호가 「대금을 받은 자」를 통신판매업자에 넣고 제20조의2제3항이
         * 중개자 고지로 그 책임을 못 면한다고 해서, <b>환급 의무자가 우리</b>다.
         * 셀러가 승인하면 우리 의무의 이행 여부를 남이 정한다.
         */
        @Test
        @DisplayName("셀러에게도 닫혀 있다")
        void isClosedToSellers() throws Exception {
            String number = requestAndRead();

            mvc.perform(post("/api/refunds/{number}/approve", number)
                            .with(user(sellerOwner)).with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("두 번째는 409 다")
        void isRejectedTwice() throws Exception {
            String number = requestAndRead();

            mvc.perform(post("/api/refunds/{number}/approve", number)
                    .with(user(admin)).with(csrf()));

            mvc.perform(post("/api/refunds/{number}/approve", number)
                            .with(user(admin)).with(csrf()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("반려에 사유가 없으면 422 다")
        void needsAReasonToReject() throws Exception {
            String number = requestAndRead();

            mvc.perform(post("/api/refunds/{number}/reject", number)
                            .with(user(admin)).with(csrf()))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    @DisplayName("목록은")
    class Listing {

        @Test
        @DisplayName("기본이 기한 임박순이다")
        void ordersByDueDate() throws Exception {
            requestAndRead();

            mvc.perform(get("/api/refunds").with(user(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].refund_number").exists())
                    .andExpect(jsonPath("$.items[0].overdue").value(false))
                    .andExpect(jsonPath("$.total").value(1));
        }

        /**
         * <b>이 줄이 `D2` R5 의 강제 지점이다.</b> 기한을 넘긴 것을 조회가 안 드러내면
         * 「박제하고 드러낸다」에서 뒷 절반이 없는 것이고, 그 위에는 아무 방벽도 없다.
         */
        @Test
        @DisplayName("기한을 넘긴 것을 표시한다")
        void marksOverdue() throws Exception {
            String number = requestAndRead();
            jdbc.sql("update refund set due_at = now() - interval '1 day' where refund_number = :n")
                    .param("n", number)
                    .update();

            mvc.perform(get("/api/refunds").with(user(admin)).param("status", "REQUESTED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].overdue").value(true));
        }

        @Test
        @DisplayName("모르는 상태로 거르면 400 이다")
        void rejectsUnknownStatusFilter() throws Exception {
            mvc.perform(get("/api/refunds").with(user(admin)).param("status", "PENDING"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("고객에게는 자기 것만 보인다")
        void isScopedToOwnOrdersForCustomers() throws Exception {
            requestAndRead();

            long stranger = fixture.insertUser("refund-api-other@test.local", "남");
            fixture.grantGlobal(stranger, "customer");

            mvc.perform(get("/api/refunds")
                            .with(user(new ShopUser(stranger, "refund-api-other@test.local", null, true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }
    }

    @Nested
    @DisplayName("주문 상세는")
    class OrderDetail {

        @Test
        @DisplayName("환불을 같이 내린다")
        void carriesRefunds() throws Exception {
            requestAndRead();

            mvc.perform(get("/api/orders/{number}", orderNumber()).with(user(buyer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$._visible_field_groups",
                            org.hamcrest.Matchers.hasItem("refund")))
                    .andExpect(jsonPath("$.refunds[0].status").value("REQUESTED"))
                    .andExpect(jsonPath("$.refunds[0].seller_order_number")
                            .value(sellerOrderNumber));
        }

        /**
         * <b>요청 사유가 응답에 없다.</b> 소비자가 쓴 자유 텍스트라 무엇이 들어올지 모르고,
         * 셀러에게 나가는 것이 제3자 제공이다(`D2` R8).
         */
        @Test
        @DisplayName("요청 사유는 안 내린다")
        void withholdsTheRequestReason() throws Exception {
            mvc.perform(post("/api/refunds").with(user(buyer)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"seller_order_number": "%s", "reason_code": "cancelled",
                             "reason": "주소를 잘못 적었어요 서울시 강남구 어딘가"}
                            """.formatted(sellerOrderNumber)));

            mvc.perform(get("/api/orders/{number}", orderNumber()).with(user(buyer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refunds[0].request_reason").doesNotExist());
        }
    }

    private org.springframework.test.web.servlet.RequestBuilder requestBy(ShopUser who) {
        return post("/api/refunds").with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"seller_order_number": "%s", "reason_code": "cancelled"}
                        """.formatted(sellerOrderNumber));
    }

    /** 요청을 하나 내고 그 번호를 돌려준다 */
    private String requestAndRead() throws Exception {
        mvc.perform(requestBy(buyer)).andExpect(status().isCreated());

        return jdbc.sql("""
                        select r.refund_number from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                         where so.order_id = :orderId
                         order by r.refund_id desc limit 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private String orderNumber() {
        return jdbc.sql("select order_number from shop_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    /** 상태를 SQL 로 민다. 전이 자체는 `11-2` 가 보는 것이다 */
    private void closeBundle(String status) {
        jdbc.sql("""
                        update seller_order set status = :status, closed_at = now()
                         where order_id = :orderId
                        """)
                .param("status", status)
                .param("orderId", orderId)
                .update();
    }

    private OrderService.Created placeOrder(long buyerId) {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, :quantity)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .param("quantity", ORDERED)
                .query(Long.class)
                .single();

        return orderService.create(buyerId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                        "서울시 강남구", "101호", null)));
    }

    private long insertSku(long sellerId, long ownerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '환불 API 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count)
                        values (:productId, :price, :stock)
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .param("stock", STOCK)
                .query(Long.class)
                .single();
    }
}
