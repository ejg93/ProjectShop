package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderActionService.Action;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderStatusService.ReturnReason;
import com.projectshop.shop.order.OrderTransitions.Payment;

/**
 * 반품 입고와 판정(청크 `43a-2`).
 *
 * <p>여기서 지키는 것 넷이다.
 *
 * <ul>
 *   <li><b>판정이 묶음과 같은 트랜잭션에서 닫힌다</b> — `43` 이 표를 세우고 `43a-1` 이 접수를
 *       열었지만 판정이 없어서 묶음이 {@code return_requested} 에서 못 움직였다</li>
 *   <li><b>셀러가 판정을 못 한다</b> — 제17조제5항이 훼손 책임의 입증을 우리에게 지웠다(`D2` R37)</li>
 *   <li><b>부담 주체가 계산이다</b> — 제18조제9항·제10항(`D2` R36)</li>
 *   <li><b>거절 복귀가 기산점을 다시 안 박는다</b> — 반복하면 소비자의 7일을 밀 수 있다(`D7`)</li>
 * </ul>
 *
 * <p><b>지연 제약은 롤백하는 테스트에서 안 돈다.</b> 이 클래스는 {@code set constraints all
 * immediate} 를 그 자리에서 불러 커밋 검사를 밟는다 — 안 부르면 `43a-1` 이 겪은 것처럼
 * <b>빌드도 CI 도 초록인데 실물이 막혀 있는</b> 상태가 된다(`stack.md`).
 */
@DisplayName("반품 판정")
class ReturnDecisionTest extends PostgresTestBase {

    @Autowired
    private OrderActionService actions;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long sellerOwner;
    private long admin;
    private long seller;
    private long sku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("rd-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        seller = fixture.insertSeller("s-rd", "반품셀러");
        fixture.verifySeller(seller);
        sku = insertSku(seller);

        sellerOwner = fixture.insertUser("rd-owner@test.local", "셀러대표");
        fixture.joinSeller(seller, sellerOwner);
        fixture.grantOrg(sellerOwner, "seller_owner", seller);

        admin = fixture.insertUser("rd-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
    }

    @Nested
    @DisplayName("누가 판정하나")
    class WhoDecides {

        /**
         * 이 청크의 요지다. 그전에는 셀러의 {@code update_status} 가 {@code return_requested} 에서
         * 열려 있어서 반품완료를 셀러가 눌렀다.
         */
        @Test
        @DisplayName("셀러는 승인을 못 한다")
        void sellerCannotApprove() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            actions.receiveReturn(sellerOwner, number, null);

            assertThatThrownBy(() -> approve(sellerOwner, number, true))
                    .as("판정이 셀러에게 열리면 셀러의 소견이 곧 결론이 된다(제17조제5항)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /**
         * <b>거절은 배송완료로 되돌리는 전이다.</b> 셀러가 부를 수 있으면 청약철회 기산점을
         * 조작할 수 있다(`D7` 「배송 후에는 셀러도 못 고친다」).
         */
        @Test
        @DisplayName("셀러는 거절을 못 한다")
        void sellerCannotReject() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            assertThatThrownBy(() -> reject(sellerOwner, number, "포장이 훼손됐다"))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /**
         * 셀러가 {@code DELIVER} 로 같은 곳에 가는 우회로가 막혔는지 본다.
         * 전이표에는 {@code return_requested → delivered} 가 있으므로 <b>막는 것은 상태 축이다.</b>
         */
        @Test
        @DisplayName("셀러는 DELIVER 로 우회하지도 못한다")
        void sellerCannotWalkBackWithDeliver() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            assertThatThrownBy(() -> actions.run(sellerOwner, number, Action.DELIVER, null))
                    .as("update_status 가 return_requested 를 들고 있으면 이것이 거절 복귀가 된다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("셀러는 입고까지 한다")
        void sellerReceives() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            assertThatCode(() -> actions.receiveReturn(sellerOwner, number, null))
                    .as("물건이 왔는지는 받아 본 셀러가 안다")
                    .doesNotThrowAnyException();

            assertThat(returnStatusOf(number)).isEqualTo("received");
        }
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        @Test
        @DisplayName("승인이 묶음을 닫고 커밋을 지난다")
        void approvedBundleCloses() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            actions.receiveReturn(sellerOwner, number, null);

            approve(admin, number, true);

            assertThat(returnStatusOf(number)).isEqualTo("approved");
            assertThat(statusOf(number)).isEqualTo("returned");
            assertThatCode(ReturnDecisionTest.this::flush)
                    .as("판정 없이 묶음만 returned 로 가면 V63 의 지연 트리거가 커밋에서 거부한다")
                    .doesNotThrowAnyException();
        }

        /**
         * 제18조제2항 1호가 환급 기산점을 「재화등을 <b>반환받은 날</b>」로 잡는다.
         * 물건이 안 왔는데 승인하면 그 날이 없는 채로 3영업일이 흐른다.
         */
        @Test
        @DisplayName("입고 없이는 승인이 안 된다")
        void approvalNeedsReceipt() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            assertThatThrownBy(() -> approve(admin, number, true))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.RETURN_NOT_RECEIVED));
        }

        @Test
        @DisplayName("다시 팔 수 있으면 재고가 돌아온다")
        void restockReturnsStock() {
            int before = stockOf(sku);
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            actions.receiveReturn(sellerOwner, number, null);

            approve(admin, number, true);

            assertThat(stockOf(sku)).isEqualTo(before);
            assertThat(lastStockReason(sku))
                    .as("취소와 반품이 한 사유로 뭉치면 왜 재고가 늘었는지에 답이 안 나온다")
                    .isEqualTo("return_restocked");
        }

        /** 파손돼 돌아온 것은 재고가 아니다. 같은 {@code approved} 라도 답이 갈린다 */
        @Test
        @DisplayName("못 파는 물건은 재고가 안 돌아온다")
        void noRestockKeepsStock() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            int afterOrder = stockOf(sku);
            actions.receiveReturn(sellerOwner, number, null);

            approve(admin, number, false);

            assertThat(stockOf(sku)).isEqualTo(afterOrder);
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        @Test
        @DisplayName("거절이 묶음을 배송완료로 되돌린다")
        void rejectedBundleGoesBack() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            reject(admin, number, "사용 흔적이 있다");

            assertThat(returnStatusOf(number)).isEqualTo("rejected");
            assertThat(statusOf(number)).isEqualTo("delivered");
            assertThatCode(ReturnDecisionTest.this::flush).doesNotThrowAnyException();
        }

        /**
         * `V63` 의 {@code return_requires_rejection_reason} 이 커밋에서 같은 것을 보지만
         * 그것은 지연이라 500 으로 나간다. 여기서 받아야 422 가 된다.
         */
        @Test
        @DisplayName("사유 없는 거절은 막힌다")
        void rejectionNeedsReason() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);

            assertThatThrownBy(() -> reject(admin, number, "  "))
                    .isInstanceOfSatisfying(ShopException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.RETURN_DECISION_REASON_REQUIRED));
        }

        /**
         * <b>이 청크가 새로 막은 것이다.</b> 거절 복귀도 {@code delivered} 로 가므로,
         * 기한을 다시 박으면 거절을 반복해서 소비자의 7일을 밀 수 있다(`D7`).
         */
        @Test
        @DisplayName("거절 복귀가 청약철회 기한을 다시 안 박는다")
        void rejectionKeepsWithdrawalDeadline() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            String frozen = withdrawalExpireOf(number);

            reject(admin, number, "사용 흔적이 있다");

            assertThat(withdrawalExpireOf(number))
                    .as("되돌릴 때마다 다시 박으면 기산점이 오늘로 밀린다")
                    .isEqualTo(frozen);
        }
    }

    @Nested
    @DisplayName("반품 배송비 부담 주체")
    class ShippingFeeBearer {

        /** 제18조제10항 — 제17조제3항의 경우는 판매자가 문다 */
        @Test
        @DisplayName("하자를 인정하면 판매자가 문다")
        void defectApprovedIsSeller() {
            String number = requestedReturn(ReturnReason.DEFECT);
            actions.receiveReturn(sellerOwner, number, null);

            approve(admin, number, false);

            assertThat(bearerOf(number)).isEqualTo("seller");
        }

        /** 제18조제9항 원칙. 예외 사유가 없다 */
        @Test
        @DisplayName("단순 변심을 인정해도 소비자가 문다")
        void changeOfMindApprovedIsConsumer() {
            String number = requestedReturn(ReturnReason.CHANGE_OF_MIND);
            actions.receiveReturn(sellerOwner, number, null);

            approve(admin, number, true);

            assertThat(bearerOf(number)).isEqualTo("consumer");
        }

        /**
         * 거절은 「제17조제3항의 경우가 <b>아니라고 판정한 것</b>」이라 제10항의 예외가 안 걸린다.
         * <b>하자로 접수됐어도 그렇다</b> — 접수 사유가 곧 결론이 아니다(제17조제5항).
         */
        @Test
        @DisplayName("하자로 접수됐어도 거절이면 소비자가 문다")
        void defectRejectedIsConsumer() {
            String number = requestedReturn(ReturnReason.DEFECT);

            reject(admin, number, "검수 결과 하자가 아니다");

            assertThat(bearerOf(number)).isEqualTo("consumer");
        }
    }

    /** 밀린 지연 제약을 그 자리에서 돌린다. 안 부르면 롤백에 묻혀서 한 번도 안 돈다 */
    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    /** 다음 단계를 한 덩어리로 보게 지연을 다시 켠다 */
    private void defer() {
        jdbc.sql("set constraints all deferred").update();
    }

    private void approve(long userId, String number, boolean restock) {
        actions.run(userId, number, Action.APPROVE_RETURN, "테스트 판정", null,
                new ReturnRequestService.Decision.Approve(restock));
    }

    private void reject(long userId, String number, String reason) {
        actions.run(userId, number, Action.REJECT_RETURN, "테스트 판정", null,
                new ReturnRequestService.Decision.Reject(reason));
    }

    /**
     * 접수까지 간 묶음. 결제 → 발송 → 배송완료 → 반품 접수다.
     *
     * <p><b>접수를 여기서 한 번 밟고 넘어간다.</b> 지연 트리거는 커밋 시점의 상태로
     * <b>지나간 이벤트까지 다시 본다</b> — 실서비스에서는 접수와 판정이 다른 트랜잭션이라
     * 각자 자기 시점에 걸리지만, 한 트랜잭션에 몰아 넣으면 접수 이벤트가
     * 「열린 반품이 없는데 묶음이 {@code return_requested} 다」로 읽힌다.
     */
    private String requestedReturn(ReturnReason reason) {
        statuses.movePayment(placeOrder(), Payment.PAID, Actor.system("테스트 결제"));
        String number = numberOf();

        actions.run(sellerOwner, number, Action.SHIP, null);
        actions.run(sellerOwner, number, Action.DELIVER, null);
        actions.run(buyer, number, Action.REQUEST_RETURN, null, reason);

        flush();
        defer();
        return number;
    }

    private String returnStatusOf(String number) {
        return jdbc.sql("""
                        select rr.status from return_request rr
                          join seller_order so on so.seller_order_id = rr.seller_order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", number)
                .query(String.class)
                .single();
    }

    private String bearerOf(String number) {
        return jdbc.sql("""
                        select rr.return_shipping_fee_bearer from return_request rr
                          join seller_order so on so.seller_order_id = rr.seller_order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", number)
                .query(String.class)
                .single();
    }

    private String statusOf(String number) {
        return jdbc.sql("select status from seller_order where seller_order_number = :number")
                .param("number", number)
                .query(String.class)
                .single();
    }

    private String withdrawalExpireOf(String number) {
        return jdbc.sql("""
                        select withdrawal_expire_at::text from seller_order
                         where seller_order_number = :number
                        """)
                .param("number", number)
                .query(String.class)
                .single();
    }

    private int stockOf(long skuId) {
        return jdbc.sql("select on_hand from sku_stock where sku_id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
    }

    private String lastStockReason(long skuId) {
        return jdbc.sql("""
                        select reason from sku_stock_movement
                         where sku_id = :skuId
                         order by sku_stock_movement_id desc
                         limit 1
                        """)
                .param("skuId", skuId)
                .query(String.class)
                .single();
    }

    private String numberOf() {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where seller_id = :sellerId
                         order by seller_order_id desc
                         limit 1
                        """)
                .param("sellerId", seller)
                .query(String.class)
                .single();
    }

    private long placeOrder() {
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyer)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc
                        .sql("insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyer)
                        .query(Long.class)
                        .single());

        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", sku)
                .query(Long.class)
                .single();

        return orderService.create(buyer, new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '반품 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
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
