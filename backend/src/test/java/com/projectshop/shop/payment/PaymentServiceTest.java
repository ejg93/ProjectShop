package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderService;

/**
 * 결제가 무엇을 남기고 무엇을 안 남기나.
 *
 * <p>두 축을 본다. <b>돈이 두 번 빠지지 않는가</b>(멱등·재시도)와
 * <b>담으면 안 되는 것이 안 담기는가</b>(`D2` R18)다. 뒤쪽은 화면에도 로그에도 안 보여서
 * 테스트가 유일한 방벽이다 — 컬럼이 없다는 사실을 사람이 눈으로 확인할 자리가 없다.
 */
@DisplayName("결제")
class PaymentServiceTest extends PostgresTestBase {

    private static final int STOCK = 10;
    private static final int ORDERED = 3;
    private static final long PRICE = 10_000;

    /** 승인되는 카드. 뒤 4자리가 결과를 가른다(`MockPaymentGateway`) */
    private static final String GOOD_CARD = "4242-4242-4242-4242";
    private static final String DECLINED_CARD = "4242-4242-4242-0000";
    private static final String TIMEOUT_CARD = "4242-4242-4242-0002";

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long skuId;
    private long orderId;
    private String orderNumber;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("pay-buyer@test.local", "결제구매자");

        long sellerId = fixture.insertSeller("s-pay", "결제셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku(sellerId);
        OrderService.Created created = placeOrder();
        orderId = created.orderId();
        orderNumber = created.orderNumber();
    }

    @Nested
    @DisplayName("승인되면")
    class Approved {

        @Test
        @DisplayName("주문이 결제완료로 간다")
        void movesOrderToPaid() {
            PaymentService.Result result = pay(GOOD_CARD);

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(orderStatus())
                    .as("결제 모듈 말고는 이 전이를 일으키는 곳이 없다(`D7`)")
                    .isEqualTo("paid");
        }

        @Test
        @DisplayName("주문에 박제된 금액 그대로 청구한다")
        void chargesTheStoredAmount() {
            PaymentService.Result result = pay(GOOD_CARD);

            assertThat(result.amount())
                    .as("요청이 금액을 안 받는다. 받으면 원하는 값으로 결제되는 길이 열린다")
                    .isEqualTo(PRICE * ORDERED + shippingFeeTotal());
            assertThat(paymentColumn("amount")).isEqualTo(String.valueOf(result.amount()));
        }

        @Test
        @DisplayName("승인번호와 카드사, 뒷 4자리만 남는다")
        void keepsOnlyTheResult() {
            pay(GOOD_CARD);

            assertThat(paymentColumn("approval_number")).isNotBlank();
            assertThat(paymentCardColumn("card_issuer")).isEqualTo("비자");
            assertThat(paymentCardColumn("card_last4")).isEqualTo("4242");
        }

        /**
         * <b>카드번호가 우리 DB 어디에도 없다</b>(`D2` R18, 여신전문금융업법 제19조).
         *
         * <p>컬럼을 안 만든 것으로 끝내지 않고 행을 통째로 훑는 이유는, 승인번호나 거절 사유처럼
         * <b>PG 가 준 문자열에 카드번호가 섞여 오는 경우</b>가 있어서다. 그때 우리는
         * "안 담았다" 고 믿는 채로 담게 된다.
         */
        @Test
        @DisplayName("카드번호가 어디에도 안 남는다")
        void storesNoCardNumber() {
            pay(GOOD_CARD);

            String row = jdbc.sql("""
                            select payment::text || coalesce(payment_card::text, '')
                              from payment
                              left join payment_card using (payment_id)
                             where order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query(String.class)
                    .single();

            assertThat(row)
                    .as("가맹점은 카드번호를 보관할 수 없다(여신전문금융업법 제19조)")
                    .doesNotContain("4242424242424242")
                    .doesNotContain(GOOD_CARD);
        }
    }

    @Nested
    @DisplayName("거절되면")
    class Declined {

        @Test
        @DisplayName("결과로 내려간다. 예외가 아니다")
        void comesBackAsAResult() {
            PaymentService.Result result = pay(DECLINED_CARD);

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.declineReason()).isEqualTo("limit_exceeded");
            assertThat(result.approvalNumber()).isNull();
        }

        @Test
        @DisplayName("실패 내역이 남는다")
        void isRecorded() {
            pay(DECLINED_CARD);

            assertThat(paymentColumn("status"))
                    .as("예외로 던지면 같이 롤백돼서 `payment:read` 가 볼 실패 내역이 없어진다")
                    .isEqualTo("failed");
        }

        @Test
        @DisplayName("주문이 닫히고 재고가 돌아온다")
        void closesOrderAndRestoresStock() {
            assertThat(stock()).isEqualTo(STOCK - ORDERED);

            pay(DECLINED_CARD);

            assertThat(orderStatus()).isEqualTo("payment_failed");
            assertThat(stock())
                    .as("안 되돌리면 거절된 주문이 재고를 물고 있어서 남이 못 산다")
                    .isEqualTo(STOCK);
        }
    }

    @Nested
    @DisplayName("같은 멱등키로 다시 오면")
    class Retransmission {

        @Test
        @DisplayName("앞의 승인을 그대로 돌려준다")
        void replaysTheSameApproval() {
            String key = UUID.randomUUID().toString();
            PaymentService.Result first = pay(key, GOOD_CARD);
            PaymentService.Result second = pay(key, GOOD_CARD);

            assertThat(second.approvalNumber())
                    .as("다른 승인번호가 나오면 같은 주문에 두 번 청구된 것이다")
                    .isEqualTo(first.approvalNumber());
        }

        @Test
        @DisplayName("결제가 하나만 남는다")
        void leavesOneRow() {
            String key = UUID.randomUUID().toString();
            pay(key, GOOD_CARD);
            pay(key, GOOD_CARD);

            assertThat(paymentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("키가 다르면 이미 낸 주문이라 막힌다")
        void isBlockedByOrderStatusWhenKeyDiffers() {
            pay(GOOD_CARD);

            assertThatThrownBy(() -> pay(GOOD_CARD))
                    .as("멱등키는 재전송만 막는다. 두 번 내는 것은 주문 상태가 막는다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED));
        }
    }

    @Nested
    @DisplayName("결제사가 응답을 안 하면")
    class GatewayTimeout {

        @Test
        @DisplayName("같은 키로 다시 불러서 승인을 받는다")
        void retriesWithTheSameKey() {
            PaymentService.Result result = pay(TIMEOUT_CARD);

            assertThat(result.status())
                    .as("키를 새로 만들어 재시도하면 결제사가 다른 요청으로 보고 두 번 승인한다(`D11`)")
                    .isEqualTo("APPROVED");
            assertThat(paymentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("낼 수 없는 주문은")
    class NotPayable {

        @Test
        @DisplayName("남의 것이면 없는 것과 같다")
        void looksMissingToOthers() {
            long stranger = fixture.insertUser("pay-stranger@test.local", "남");

            assertThatThrownBy(() -> payments.pay(stranger, UUID.randomUUID().toString(),
                    new PaymentService.Command(orderNumber, PaymentMethod.CARD, GOOD_CARD)))
                    .as("가르면 번호를 두드려서 주문이 몇 개인지 셀 수 있다(`D5`)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("PG 를 부르기 전에 걸린다")
        void isStoppedBeforeTheGatewayIsCalled() {
            pay(DECLINED_CARD);

            assertThatThrownBy(() -> pay(GOOD_CARD))
                    .isInstanceOf(ShopException.class);

            assertThat(paymentCount())
                    .as("나중에 보면 이미 승인된 카드를 되돌려야 한다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("제약이")
    class Constraints {

        @Test
        @DisplayName("한 주문에 승인을 두 번 못 적게 막는다")
        void allowsOneApprovalPerOrder() {
            pay(GOOD_CARD);

            assertThatThrownBy(() -> insertApproval("M99999999"))
                    .as("앱이 상태를 보지만 그건 강제 지점 3위라 새 입구가 생기면 빠뜨린다(`D23`)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("뒷 4자리 자리에 카드번호 전체를 못 넣게 막는다")
        void rejectsFullCardNumberInLast4() {
            long paymentId = insertApproval("M1");

            assertThatThrownBy(() -> jdbc.sql("""
                            insert into payment_card (payment_id, card_issuer, card_last4)
                            values (:paymentId, '비자', '4242424242424242')
                            """)
                    .param("paymentId", paymentId)
                    .update())
                    .as("여신전문금융업법 제19조가 가맹점의 카드정보 보관을 금지한다(`D2` R18)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private PaymentService.Result pay(String cardNumber) {
        return pay(UUID.randomUUID().toString(), cardNumber);
    }

    private PaymentService.Result pay(String idempotencyKey, String cardNumber) {
        return payments.pay(userId, idempotencyKey,
                new PaymentService.Command(orderNumber, PaymentMethod.CARD, cardNumber));
    }

    /** 승인 하나를 직접 적는다. 카드 정보는 갈라진 표라 여기서 안 넣는다(`D2` R9) */
    private long insertApproval(String approvalNumber) {
        return jdbc.sql("""
                        insert into payment (order_id, status, method, amount,
                                             approval_number)
                        values (:orderId, 'approved', 'card', 1000, :approvalNumber)
                        returning payment_id
                        """)
                .param("orderId", orderId)
                .param("approvalNumber", approvalNumber)
                .query(Long.class)
                .single();
    }

    private OrderService.Created placeOrder() {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", userId)
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

        return orderService.create(userId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)));
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '결제 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, :stock from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .param("stock", STOCK)
                .query(Long.class)
                .single();
    }

    private String orderStatus() {
        return jdbc.sql("select status from shop_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private long shippingFeeTotal() {
        return jdbc.sql("select shipping_fee_total from shop_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private String paymentColumn(String column) {
        return jdbc.sql("select %s::text from payment where order_id = :orderId".formatted(column))
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    /** 카드 정보는 보존분 분리로 갈라져 있다(`D2` R9). 결제 행과 같이 읽을 때 쓴다 */
    private String paymentCardColumn(String column) {
        return jdbc.sql("""
                        select c.%s::text
                          from payment p join payment_card c on c.payment_id = p.payment_id
                         where p.order_id = :orderId
                        """.formatted(column))
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private int paymentCount() {
        return jdbc.sql("select count(*) from payment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
    }

    private int stock() {
        return jdbc.sql("select on_hand from sku_stock where sku_id = :id")
                .param("id", skuId)
                .query(Integer.class)
                .single();
    }
}
