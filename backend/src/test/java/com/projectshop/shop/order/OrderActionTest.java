package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
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
import com.projectshop.shop.order.OrderTransitions.Payment;

/**
 * 사람이 주문을 옮기는 경로.
 *
 * <p>여기서 지키는 것 셋이다. <b>동작마다 열리는 상태가 다르고</b>(`V20` 이 동작을 가른 이유),
 * <b>고객과 셀러가 서로의 동작을 못 부르고</b>, <b>법이 정한 두 조건이 반품을 막는다</b>(`D2` R3·R4).
 *
 * <p>셋 다 틀려도 화면은 정상으로 보인다 — 셀러가 대신 확정을 누르면 정산이 당겨질 뿐이고,
 * 기한 지난 반품은 그냥 접수된다.
 */
@DisplayName("주문 처리")
class OrderActionTest extends PostgresTestBase {

    @Autowired
    private OrderActionService actions;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long alphaOwner;
    private long alpha;
    private long alphaSku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("oa-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        alpha = fixture.insertSeller("s-oa-alpha", "알파");
        fixture.verifySeller(alpha);
        alphaSku = insertSku(alpha, "알파 상품", null);

        alphaOwner = fixture.insertUser("oa-alpha-owner@test.local", "알파대표");
        fixture.joinSeller(alpha, alphaOwner);
        fixture.grantOrg(alphaOwner, "seller_owner", alpha);
    }

    @Nested
    @DisplayName("동작과 상태")
    class ActionAndStatus {

        @Test
        @DisplayName("셀러가 발송하면 배송중이 된다")
        void sellerShips() {
            String number = paidShipment();

            actions.run(alphaOwner, number, Action.SHIP, null);

            assertThat(statusOf(number)).isEqualTo("shipping");
        }

        /**
         * `V20` 이 동작을 가른 첫 번째 이유다. 한 동작으로 두면 고객이 자기 주문을
         * 발송·배송완료까지 밀어서, 셀러가 물건을 안 보낸 주문이 확정까지 간다.
         */
        @Test
        @DisplayName("고객은 발송을 못 부른다")
        void buyerCannotShip() {
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(buyer, number, Action.SHIP, null))
                    .as("고객에게 update_status 가 열리면 셀러 없이 주문이 진행된다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /**
         * 두 번째 이유다. 확정은 정산 대상이 되는 사건이라 셀러가 누를 수 있으면
         * 자기 정산 시점을 당긴다(`D7`).
         */
        @Test
        @DisplayName("셀러는 구매확정을 못 부른다")
        void sellerCannotConfirm() {
            String number = deliveredShipment();

            assertThatThrownBy(() -> actions.run(alphaOwner, number, Action.CONFIRM, null))
                    .as("셀러가 확정을 누르면 청약철회가 살아 있는 주문이 정산으로 넘어간다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("고객이 배송받은 것을 확정한다")
        void buyerConfirms() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.CONFIRM, null);

            assertThat(statusOf(number)).isEqualTo("confirmed");
        }

        /** 상태 축이 막는다. 전이표에도 없지만 권한에서 먼저 걸려야 시도가 감사에 남는다 */
        @Test
        @DisplayName("배송 전에는 구매확정이 안 열린다")
        void confirmClosedBeforeDelivery() {
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(buyer, number, Action.CONFIRM, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /** `D7` 이 `preparing → cancelled` 를 "고객 또는 셀러" 로 정했다 */
        @Test
        @DisplayName("취소는 고객도 셀러도 부른다")
        void bothCanCancel() {
            actions.run(buyer, paidShipment(), Action.CANCEL, null);

            assertThatCode(() -> actions.run(alphaOwner, paidShipment(), Action.CANCEL, null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("보이는 경계")
    class Visibility {

        /** 결제가 안 끝난 묶음은 조회에서와 마찬가지로 없는 것이다 */
        @Test
        @DisplayName("결제 전 묶음은 없는 것과 같다")
        void unpaidLooksMissing() {
            placeOrder(List.of(alphaSku));
            String number = numberOf(alpha);

            assertThatThrownBy(() -> actions.run(alphaOwner, number, Action.SHIP, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /** 403 이면 번호를 훑어 실재하는 묶음의 지도가 그려진다(`D5`) */
        @Test
        @DisplayName("남의 묶음도 없는 것과 같은 답이다")
        void othersLookMissing() {
            long stranger = fixture.insertUser("oa-stranger@test.local", "남");
            fixture.grantGlobal(stranger, "customer");
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(stranger, number, Action.CANCEL, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("반품 접수")
    class ReturnRequest {

        @Test
        @DisplayName("배송받은 것은 반품 접수가 된다")
        void plainReturnPasses() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.REQUEST_RETURN, null);

            assertThat(statusOf(number)).isEqualTo("return_requested");
        }

        /**
         * 전자상거래법 제17조제2항(`D2` R4). 상품 속성이라 상태 축으로는 표현이 안 되고,
         * 이것을 안 막으면 주문 제작 상품이 반품 접수까지 간 뒤에 사람이 손으로 되돌려야 한다.
         */
        @Test
        @DisplayName("청약철회 제한 상품은 반품이 막힌다")
        void restrictedProductBlocked() {
            long restrictedSku = insertSku(alpha, "주문 제작 상품", "made_to_order");
            String number = deliveredShipment(restrictedSku);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /** 묶음 하나에 섞여 있어도 막는다. 취소·반품의 최소 단위가 셀러 묶음이다(`D7`) */
        @Test
        @DisplayName("한 항목만 제한이어도 묶음 전체가 막힌다")
        void oneRestrictedItemBlocksBundle() {
            long restrictedSku = insertSku(alpha, "주문 제작 상품", "made_to_order");
            String number = deliveredShipment(alphaSku, restrictedSku);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /**
         * 전자상거래법 제17조(`D2` R3). 배송완료 때 박제한 값을 읽는다 —
         * 여기서 다시 계산하면 달력이 바뀌었을 때 지나간 주문의 기한까지 흔들린다.
         */
        @Test
        @DisplayName("청약철회 기간이 지나면 반품이 막힌다")
        void expiredPeriodBlocked() {
            String number = deliveredShipment();
            expireWithdrawal(number);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        /**
         * 배치와 관리자도 같은 검사를 지난다. 서비스가 아니라 경로마다 걸면
         * 새 입구가 생겼을 때 빠뜨린다(`D23` 「축 2 — 강제 지점」).
         */
        @Test
        @DisplayName("전이 서비스를 직접 불러도 제한이 걸린다")
        void checkLivesInTransition() {
            long restrictedSku = insertSku(alpha, "복제 가능 재화", "copyable_media");
            String number = deliveredShipment(restrictedSku);
            long sellerOrderId = idOf(number);

            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId,
                    OrderTransitions.Shipment.RETURN_REQUESTED, Actor.system("직접 호출")))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }
    }

    @Nested
    @DisplayName("이력의 행위자")
    class ActorType {

        /** 역할 이름이 아니라 대상 행과의 관계로 정한다. 같은 사람이 자기 가게에서 살 수 있다 */
        @Test
        @DisplayName("주문자가 옮기면 customer 로 남는다")
        void buyerIsCustomer() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.CONFIRM, null);

            assertThat(lastActorType(number)).isEqualTo("customer");
        }

        @Test
        @DisplayName("셀러 소속이 옮기면 seller 로 남는다")
        void memberIsSeller() {
            String number = paidShipment();

            actions.run(alphaOwner, number, Action.SHIP, null);

            assertThat(lastActorType(number)).isEqualTo("seller");
        }

        /**
         * 주문자도 셀러 소속도 아닌데 판정을 통과했으면 `all` 스코프다. 정상 경로가 아니라서
         * 사유가 없으면 나중에 데이터가 왜 이 모양인지 아무도 모른다(`D7`).
         */
        @Test
        @DisplayName("관리자는 사유가 없으면 못 옮긴다")
        void adminNeedsReason() {
            long admin = fixture.insertUser("oa-admin@test.local", "관리자");
            fixture.grantGlobal(admin, "admin");
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(admin, number, Action.CANCEL, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.TRANSITION_REASON_REQUIRED));

            actions.run(admin, number, Action.CANCEL, "고객 전화 요청");

            assertThat(lastActorType(number)).isEqualTo("admin");
        }
    }

    /** 결제까지 끝난 묶음. 인자가 없으면 기본 상품 하나짜리다 */
    private String paidShipment(long... skuIds) {
        List<Long> ids = skuIds.length == 0
                ? List.of(alphaSku)
                : Arrays.stream(skuIds).boxed().toList();

        statuses.movePayment(placeOrder(ids), Payment.PAID, Actor.system("테스트 결제"));
        return numberOf(alpha);
    }

    /** 셀러가 발송하고 배송을 끝낸 상태. 기한 둘이 여기서 박제된다 */
    private String deliveredShipment(long... skuIds) {
        String number = paidShipment(skuIds);
        actions.run(alphaOwner, number, Action.SHIP, null);
        actions.run(alphaOwner, number, Action.DELIVER, null);
        return number;
    }

    /** 기한을 손으로 넘긴다. 7일을 실제로 기다릴 수 없어서다 */
    private void expireWithdrawal(String sellerOrderNumber) {
        jdbc.sql("""
                        update seller_order
                           set withdrawal_expire_at = now() - interval '1 day'
                         where seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .update();
    }

    private String statusOf(String sellerOrderNumber) {
        return jdbc.sql("select status from seller_order where seller_order_number = :number")
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private long idOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select seller_order_id from seller_order
                         where seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(Long.class)
                .single();
    }

    private String lastActorType(String sellerOrderNumber) {
        return jdbc.sql("""
                        select h.actor_type
                          from order_status_history h
                          join seller_order so on so.seller_order_id = h.seller_order_id
                         where so.seller_order_number = :number
                         order by h.order_status_history_id desc
                         limit 1
                        """)
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private String numberOf(long sellerId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where seller_id = :sellerId
                         order by seller_order_id desc
                         limit 1
                        """)
                .param("sellerId", sellerId)
                .query(String.class)
                .single();
    }

    private long placeOrder(List<Long> skuIds) {
        long cartId = cartOfBuyer();

        List<Long> cartItemIds = skuIds.stream()
                .map(skuId -> jdbc.sql("""
                                insert into cart_item (cart_id, sku_id, quantity)
                                values (:cartId, :skuId, 1)
                                returning cart_item_id
                                """)
                        .param("cartId", cartId)
                        .param("skuId", skuId)
                        .query(Long.class)
                        .single())
                .toList();

        return orderService.create(buyer, new OrderService.Command(cartItemIds,
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    /**
     * 산 사람의 장바구니. <b>사람당 하나다</b>({@code cart_user_id_key}) —
     * 한 테스트가 주문을 둘 이상 만들므로 매번 새로 넣으면 두 번째에서 깨진다.
     */
    private long cartOfBuyer() {
        return jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyer)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc
                        .sql("insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyer)
                        .query(Long.class)
                        .single());
    }

    /** @param restrictionReason 청약철회 제한 사유. 제한이 없으면 null 이다(`V13` 의 check 가 짝을 강제한다) */
    private long insertSku(long sellerId, String productName, String restrictionReason) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status,
                                             is_withdrawal_restricted, withdrawal_restriction_reason)
                        values (:sellerId, :userId, :name, 'on_sale',
                                :restricted, :reason)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .param("name", productName)
                .param("restricted", restrictionReason != null)
                .param("reason", restrictionReason)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price, stock_count)
                        values (:productId, 10000, 10)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
