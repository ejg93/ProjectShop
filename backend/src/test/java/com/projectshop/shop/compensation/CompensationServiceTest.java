package com.projectshop.shop.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.order.OrderStatusService;
import com.projectshop.shop.settlement.SettlementService;

/**
 * 손해배상 판정(`43a-4c`, `D2` R38·R39).
 *
 * <p>여기서 지키는 것 셋이다 — <b>판정은 관리자만 하고</b>, <b>판정과 사유가 같이 남고</b>, <b>셀러가 무는 판정이 그 달
 * 정산서에 빠지는 줄로 선다</b>. 셋째가 빠지면 판정은 서는데 셀러 정산이 그대로라 우리가 배상액을 두 번 문다.
 */
@DisplayName("손해배상 판정")
class CompensationServiceTest extends PostgresTestBase {

    private static final long AMOUNT = 5_000L;

    @Autowired
    private CompensationService compensations;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private SettlementService settlements;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long admin;
    private long sellerId;
    private String number;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        long buyer = fixture.insertUser("comp-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");
        admin = fixture.insertUser("comp-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");

        sellerId = fixture.insertSeller("s-comp", "배상셀러");
        fixture.verifySeller(sellerId);

        long orderId = placeOrder(buyer, insertSku(buyer));
        statuses.markPaid(orderId, "테스트 결제");
        number = jdbc.sql("select seller_order_number from seller_order where order_id = :id")
                .param("id", orderId).query(String.class).single();
    }

    @Test
    @DisplayName("판정과 사유가 같이 남는다")
    void decisionKeepsItsReason() {
        compensations.decide(admin, number, command(CompensationBearer.SELLER));

        CompensationService.Listing listing = compensations.list(admin, number);

        assertThat(listing.items()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo("NON_DELIVERY");
            assertThat(entry.bearer()).isEqualTo("SELLER");
            assertThat(entry.amount()).isEqualTo(AMOUNT);
            assertThat(entry.reason()).isEqualTo("택배 분실, 재발송 불가");
        });
        assertThat(listing.allowedActions()).containsExactly("COMPENSATE");
    }

    @Test
    @DisplayName("셀러는 판정을 못 한다 — 없는 묶음과 같은 답이다")
    void sellerCannotDecide() {
        long owner = fixture.insertUser("comp-owner@test.local", "대표");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        assertThatThrownBy(() -> compensations.decide(owner, number, command(CompensationBearer.PLATFORM)))
                .as("자기 몫에서 빠질 금액을 스스로 정하게 된다")
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("감사자는 보기만 하고 판정 버튼을 못 받는다")
    void auditorOnlyReads() {
        long auditor = fixture.insertUser("comp-auditor@test.local", "감사자");
        fixture.grantGlobal(auditor, "auditor");
        compensations.decide(admin, number, command(CompensationBearer.SELLER));

        assertThat(compensations.list(auditor, number).items()).hasSize(1);
        assertThat(compensations.list(auditor, number).allowedActions()).isEmpty();
        assertThatThrownBy(() -> compensations.decide(auditor, number, command(CompensationBearer.SELLER)))
                .isInstanceOf(ShopException.class);
    }

    @Test
    @DisplayName("그 묶음의 것이 아닌 문의는 못 잇는다")
    void rejectsAnUnrelatedInquiry() {
        assertThatThrownBy(() -> compensations.decide(admin, number, new CompensationService.Command(
                        CompensationKind.LATE_DELIVERY, CompensationBearer.SELLER, AMOUNT, "늦은 도착",
                        "Q-20260923-ABCDEF")))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.COMPENSATION_INQUIRY_MISMATCH));
    }

    /**
     * 판정이 정산서에 실리나 — 이 청크의 닫힘이다. <b>확정 거래가 없는 셀러도 정산서가 서야 한다</b> —
     * 미인도 배상은 대개 그런 달에 오고, 정산이 그 셀러를 안 고르면 판정이 어느 정산서에도 안 실린다.
     */
    @Test
    @DisplayName("셀러가 무는 판정은 그 달 정산서에 빠지는 줄로 선다 — 거래가 없는 셀러라도")
    void sellerBorneLandsOnTheStatement() {
        compensations.decide(admin, number, command(CompensationBearer.SELLER));
        compensations.decide(admin, number, command(CompensationBearer.PLATFORM));
        backdateDecisions(LocalDate.of(2026, 5, 15));

        settlements.close(LocalDate.of(2026, 5, 31));

        assertThat(jdbc.sql("""
                        select i.amount from settlement_item i
                          join settlement s on s.settlement_id = i.settlement_id
                         where s.seller_id = :sellerId and i.kind = 'compensation'
                        """)
                .param("sellerId", sellerId).query(Long.class).list())
                .as("플랫폼이 무는 것은 안 실린다 — 우리 귀책을 셀러 몫에서 빼면 그 정산서가 거짓이다")
                .containsExactly(-AMOUNT);
    }

    /**
     * 범위는 `V107` 의 검사 블록이 적용 때 한 번 본다. 뒤의 마이그레이션이 넓혀도 그 블록은 다시 안 돈다 —
     * 그래서 지금 DB 의 부여를 매번 잰다(마무리 45차 독립 리뷰).
     */
    @Test
    @DisplayName("판정은 관리자에게만, 조회는 관리자·감사자에게만 열려 있다")
    void grantsStayWithinTheDecidedScopes() {
        List<String> grants = jdbc.sql("""
                        select r.code || ':' || p.action || ':' || rp.scope || ':' || rp.effect
                          from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where p.resource = 'compensation'
                         order by 1
                        """)
                .query(String.class)
                .list();

        assertThat(grants).containsExactly(
                "admin:decide:all:allow", "admin:read:all:allow",
                "auditor:decide:all:deny", "auditor:read:all:allow");
    }

    private CompensationService.Command command(CompensationBearer bearer) {
        return new CompensationService.Command(CompensationKind.NON_DELIVERY, bearer, AMOUNT,
                "택배 분실, 재발송 불가", null);
    }

    private void backdateDecisions(LocalDate day) {
        jdbc.sql("""
                        update compensation set decided_at = (cast(:day as date) + time '12:00') at time zone 'Asia/Seoul'
                         where seller_order_id = (select seller_order_id from seller_order
                                                   where seller_order_number = :number)
                        """)
                .param("day", day)
                .param("number", number)
                .update();
    }

    private long placeOrder(long userId, long skuId) {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", userId).query(Long.class).single();
        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity) values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId).param("skuId", skuId).query(Long.class).single();

        return orderService.create(userId, new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long createdBy) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '배상 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId).param("userId", createdBy).query(Long.class).single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat) values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand) select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId).query(Long.class).single();
    }
}
