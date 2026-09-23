package com.projectshop.shop.stats;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 매출 통계 입구(`41`).
 *
 * <p><b>첫 시험이 강제 지점이다</b> — 합계는 숫자 하나라 남의 매출이 섞여도 눈으로 못 가린다. 셀러 둘을
 * 같은 날 팔게 하고 대표가 자기 몫만 받는지를 잰다.
 */
@DisplayName("매출 통계 입구")
class SalesStatsApiTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderService orders;

    @Autowired
    private PaymentService payments;

    @Autowired
    private DailySalesBatch batch;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private ShopUser admin;
    private ShopUser ownerA;
    private ShopUser staffA;
    private long skuA;
    private long skuB;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("sales-stats-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");
        admin = globalUser("sales-stats-admin@test.local", "admin");

        long sellerA = fixture.insertSeller("s-stats-a", "통계셀러A");
        fixture.verifySeller(sellerA);
        ownerA = member(sellerA, "sales-stats-owner-a@test.local", "seller_owner");
        staffA = member(sellerA, "sales-stats-staff-a@test.local", "seller_staff");
        skuA = insertSku(sellerA, ownerA.id());

        long sellerB = fixture.insertSeller("s-stats-b", "통계셀러B");
        fixture.verifySeller(sellerB);
        ShopUser ownerB = member(sellerB, "sales-stats-owner-b@test.local", "seller_owner");
        skuB = insertSku(sellerB, ownerB.id());

        today = LocalDate.now(BusinessCalendar.ZONE);
    }

    @Test
    @DisplayName("대표는 자기 셀러의 합만 받는다")
    void ownerSeesOwnSellerOnly() throws Exception {
        placeAndPay(skuA, 2);
        placeAndPay(skuB, 1);

        stats(ownerA, today, today.plusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].paid_amount").value(2 * PRICE))
                .andExpect(jsonPath("$.total.order_count").value(1))
                .andExpect(jsonPath("$.total.net_amount").value(2 * PRICE));
    }

    @Test
    @DisplayName("관리자는 모든 셀러의 합을 받는다 — 원장 합과 같다")
    void adminSeesEverySeller() throws Exception {
        placeAndPay(skuA, 2);
        placeAndPay(skuB, 1);

        long ledger = jdbc.sql("""
                        select coalesce(sum(oi.line_amount - oi.discount_amount), 0)
                          from payment p
                          join seller_order so on so.order_id = p.order_id
                          join order_item oi on oi.seller_order_id = so.seller_order_id
                         where p.status = 'approved'
                           and (p.created_at at time zone 'Asia/Seoul')::date = :day
                        """)
                .param("day", today)
                .query(Long.class)
                .single();

        stats(admin, today, today.plusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.paid_amount").value(ledger));
    }

    @Test
    @DisplayName("직원과 고객은 403 이다")
    void isClosedToStaffAndCustomers() throws Exception {
        stats(staffA, today, today.plusDays(1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("sales-stats-forbidden")));
        stats(new ShopUser(buyerId, "sales-stats-buyer@test.local", null, true), today, today.plusDays(1))
                .andExpect(status().isForbidden());
    }

    /**
     * 배치가 성공한 날은 표를 읽고, 성공 안 한 날은 원장을 읽는다. 표를 일부러 1원 틀리게 고쳐서 어느 길을 탔는지 가른다.
     */
    @Test
    @DisplayName("집계된 날은 표에서, 안 된 날은 원장에서 읽는다")
    void readsStoredDaysAndCountsTheRest() throws Exception {
        LocalDate yesterday = today.minusDays(1);
        LocalDate before = today.minusDays(2);
        movePaymentTo(placeAndPay(skuA, 1), yesterday);
        movePaymentTo(placeAndPay(skuA, 1), before);

        batch.runFor(yesterday);
        jdbc.sql("update seller_daily_sales set paid_amount = paid_amount + 1 where sales_date = :day")
                .param("day", yesterday)
                .update();

        stats(ownerA, before, today)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].paid_amount").value(PRICE))
                .andExpect(jsonPath("$.days[1].paid_amount").value(PRICE + 1));
    }

    /**
     * 표에서 읽는 길도 스코프를 지난다(마무리 45차 독립 리뷰). 첫 시험은 오늘(원장 길)만 지나서, 어제까지의 모든 날이
     * 지나는 표 길의 조건을 빼도 초록이었다.
     */
    @Test
    @DisplayName("집계된 날도 대표는 자기 셀러의 합만 받는다")
    void ownerSeesOwnSellerOnlyOnStoredDays() throws Exception {
        LocalDate yesterday = today.minusDays(1);
        movePaymentTo(placeAndPay(skuA, 2), yesterday);
        movePaymentTo(placeAndPay(skuB, 1), yesterday);
        batch.runFor(yesterday);

        stats(ownerA, yesterday, today)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.paid_amount").value(2 * PRICE));
    }

    /**
     * 범위는 `V103` 의 검사 블록이 적용 때 한 번 본다. 뒤의 마이그레이션이 넓혀도 그 블록은 다시 안 돈다 —
     * 그래서 지금 DB 의 부여를 매번 잰다(마무리 45차 독립 리뷰).
     */
    @Test
    @DisplayName("매출 통계는 대표에게 셀러 범위로, 관리자·감사자에게 전부로만 열려 있다")
    void grantsStayWithinTheDecidedScopes() {
        List<String> grants = jdbc.sql("""
                        select r.code || ':' || rp.scope
                          from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where p.resource = 'sales_stats' and rp.effect = 'allow'
                         order by 1
                        """)
                .query(String.class)
                .list();

        org.assertj.core.api.Assertions.assertThat(grants)
                .containsExactly("admin:all", "auditor:all", "seller_owner:seller");
    }

    @Test
    @DisplayName("기간이 비었거나 1년을 넘기면 400 이다")
    void rejectsBadRanges() throws Exception {
        stats(admin, today, today)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("stats-range-invalid")));
        stats(admin, today.minusDays(SalesStatsQuery.MAX_DAYS + 1), today)
                .andExpect(status().isBadRequest());
    }

    private ResultActions stats(ShopUser viewer, LocalDate from, LocalDate to) throws Exception {
        return mvc.perform(get("/api/sales-stats")
                .param("from", from.toString())
                .param("to", to.toString())
                .with(user(viewer)));
    }

    private ShopUser globalUser(String email, String role) {
        long id = fixture.insertUser(email, role);
        fixture.grantGlobal(id, role);
        return new ShopUser(id, email, null, true);
    }

    private ShopUser member(long sellerId, String email, String role) {
        long id = fixture.insertUser(email, role);
        fixture.joinSeller(sellerId, id);
        fixture.grantOrg(id, role, sellerId);
        return new ShopUser(id, email, null, true);
    }

    private void movePaymentTo(long orderId, LocalDate day) {
        jdbc.sql("""
                        update payment set created_at = (cast(:day as date) + time '12:00') at time zone 'Asia/Seoul'
                         where order_id = :orderId
                        """)
                .param("day", day)
                .param("orderId", orderId)
                .update();
    }

    /** 주문하고 결제한다. 주문의 내부 키를 돌려준다 */
    private long placeAndPay(long skuId, int quantity) {
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

    private long insertSku(long sellerId, long ownerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '통계 상품', 'on_sale')
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
