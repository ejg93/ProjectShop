package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 주문 스키마가 `money-invariants.md` 의 등식을 실제로 막는지 본다.
 *
 * <p>등식을 문서에만 적어 두면 아무것도 안 막는다. 여기서 보는 것은 계산이 맞는지가 아니라
 * <b>틀린 값이 DB 에 들어가지 못하는지</b>다 — 앱을 안 거치는 입구에서도 막혀야 한다.
 *
 * <p><b>지연 트리거는 커밋 시점에 돈다.</b> 테스트는 {@code @Transactional} 이라 커밋을 안 하므로
 * 그냥 두면 검사가 한 번도 실행되지 않고 전부 초록이 된다. {@link #flush()} 가
 * {@code set constraints all immediate} 로 그 자리에서 밀린 검사를 돌린다.
 */
@DisplayName("주문 스키마")
class OrderSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long sellerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("buyer@test.local", "구매자");
        sellerId = fixture.insertSeller("s-order", "주문셀러");
        skuId = insertSku(sellerId, 10_000L);
    }

    @Nested
    @DisplayName("항목 등식은")
    class ItemEquations {

        @Test
        @DisplayName("금액이 단가 × 수량과 다르면 안 들어간다")
        void rejectsWrongLineAmount() {
            long sellerOrderId = anOrderWithSellerOrder();

            assertThatThrownBy(() -> insertItemRaw(sellerOrderId, 10_000L, 2, 10_000L, 1000, 1000L))
                    .as("합계는 원본과 어긋날 수 있는 유일한 종류의 값이다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("수수료가 절사 결과와 다르면 안 들어간다")
        void rejectsWrongCommission() {
            long sellerOrderId = anOrderWithSellerOrder();

            // 10,000 × 10% = 1,000 인데 1,001 을 넣는다.
            assertThatThrownBy(() -> insertItemRaw(sellerOrderId, 10_000L, 1, 10_000L, 1000, 1001L))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("원 미만은 버린다 — 반올림이 아니다")
        void truncatesTowardZero() {
            long sellerOrderId = anOrderWithSellerOrder();

            // 100,005 × 10% = 10,000.5. 반올림이면 10,001 이고 버림이면 10,000 이다.
            assertThatCode(() -> insertItemRaw(sellerOrderId, 100_005L, 1, 100_005L, 1000, 10_000L))
                    .as("버림을 고른 이유는 결과가 한 방향으로만 어긋나서 검증이 쉽기 때문이다(D8)")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("수량이 0 이면 안 들어간다")
        void rejectsZeroQuantity() {
            long sellerOrderId = anOrderWithSellerOrder();

            assertThatThrownBy(() -> insertItemRaw(sellerOrderId, 10_000L, 0, 0L, 1000, 0L))
                    .as("0개를 산다는 뜻이 없다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("요율이 만분율 범위를 벗어나면 안 들어간다")
        void rejectsCommissionBpOutOfRange() {
            long sellerOrderId = anOrderWithSellerOrder();

            assertThatThrownBy(() -> insertItemRaw(sellerOrderId, 10_000L, 1, 10_000L, 10_001, 10_001L))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("항목별 절사는")
    class PerItemTruncation {

        @Test
        @DisplayName("전체를 한 번에 자른 값과 다를 수 있고 그게 맞는 값이다")
        void differsFromWholeOrderTruncation() {
            long orderId = insertOrder("20260809-7QX4M3", 10L, 0L, 0L, 10L);
            long sellerOrderId = insertSellerOrder(orderId, sellerId, 0L);

            // 5원짜리 둘. 항목마다 5 × 10% = 0.5 → 0 이라 수수료 합이 0 이다.
            // 전체에서 자르면 10 × 10% = 1 이 된다. 1원이 갈린다.
            insertItem(sellerOrderId, 5L, 1, 1000);
            insertItem(sellerOrderId, 5L, 1, 1000);

            flush();

            assertThat(commissionTotalOf(orderId))
                    .as("항목별로 잘라 두면 부분 환불이 뺄셈 하나로 끝난다(D8)")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("주문번호는")
    class OrderNumber {

        @Test
        @DisplayName("날짜 8자리 + 난수 6자리 형식만 받는다")
        void requiresFormat() {
            assertThatThrownBy(() -> insertOrder("7QX4M2", 0L, 0L, 0L, 0L))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("혼동되는 글자는 안 받는다")
        void rejectsAmbiguousLetters() {
            assertThatThrownBy(() -> insertOrder("20260809-7QX4MO", 0L, 0L, 0L, 0L))
                    .as("전화로 번호를 부를 때 O 와 0 을 잘못 듣는다(D9)")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("숫자 1 도 안 받는다")
        void rejectsDigitOne() {
            assertThatThrownBy(() -> insertOrder("20260809-4F2K91", 0L, 0L, 0L, 0L))
                    .as("`D9` 가 예시로 쓰던 번호다. 규칙을 적은 문서가 그 규칙을 어기고 있었고 "
                            + "제약을 걸고 나서야 드러났다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("같은 번호가 두 번 들어가지 않는다")
        void isUnique() {
            insertOrder("20260809-7QX4M2", 0L, 0L, 0L, 0L);

            assertThatThrownBy(() -> insertOrder("20260809-7QX4M2", 0L, 0L, 0L, 0L))
                    .as("충돌하면 다시 뽑는다. 막는 것이 이 인덱스다(D9)")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("결제 금액은")
    class PayableAmount {

        @Test
        @DisplayName("상품 금액 + 배송비와 다르면 안 들어간다")
        void mustEqualTotalPlusShipping() {
            assertThatThrownBy(() -> insertOrder("20260809-4F2K92", 10_000L, 1_000L, 3_000L, 12_000L))
                    .as("한 행 안에서 끝나는 등식이라 check 다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("합계는")
    class Totals {

        @Test
        @DisplayName("항목 합과 맞으면 통과한다")
        void passesWhenConsistent() {
            long orderId = insertOrder("20260809-4F2K93", 20_000L, 2_000L, 3_000L, 23_000L);
            long sellerOrderId = insertSellerOrder(orderId, sellerId, 3_000L);
            insertItem(sellerOrderId, 10_000L, 2, 1000);

            assertThatCode(OrderSchemaTest.this::flush).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("주문 금액이 항목 합과 다르면 커밋이 막힌다")
        void rejectsWrongTotalAmount() {
            long orderId = insertOrder("20260809-4F2K94", 99_999L, 1_000L, 0L, 99_999L);
            long sellerOrderId = insertSellerOrder(orderId, sellerId, 0L);
            insertItem(sellerOrderId, 10_000L, 1, 1000);

            assertThatThrownBy(OrderSchemaTest.this::flush)
                    .as("여러 행에 걸친 등식이라 check 로 못 걸고 트리거로 간다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("배송비 합이 셀러 주문의 배송비와 다르면 커밋이 막힌다")
        void rejectsWrongShippingTotal() {
            long orderId = insertOrder("20260809-4F2K95", 10_000L, 1_000L, 5_000L, 15_000L);
            long sellerOrderId = insertSellerOrder(orderId, sellerId, 3_000L);
            insertItem(sellerOrderId, 10_000L, 1, 1000);

            assertThatThrownBy(OrderSchemaTest.this::flush)
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("트랜잭션 중간에는 안 맞아도 된다")
        void toleratesMidTransactionMismatch() {
            // 주문을 넣는 순간 항목 합은 0 이다. 즉시 트리거였다면 이 줄에서 이미 깨진다.
            long orderId = insertOrder("20260809-4F2K96", 10_000L, 1_000L, 0L, 10_000L);
            long sellerOrderId = insertSellerOrder(orderId, sellerId, 0L);
            insertItem(sellerOrderId, 10_000L, 1, 1000);

            assertThatCode(OrderSchemaTest.this::flush)
                    .as("커밋할 때 맞으면 된다 — 참조 방향 때문에 주문이 항목보다 먼저 들어간다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("셀러 주문이 없는 주문은 커밋이 막힌다")
        void rejectsOrderWithoutSellerOrder() {
            insertOrder("20260809-4F2K97", 0L, 0L, 0L, 0L);

            assertThatThrownBy(OrderSchemaTest.this::flush)
                    .as("합이 0 으로 맞아떨어져서 금액 등식만으로는 안 잡힌다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("항목이 없는 셀러 주문은 커밋이 막힌다")
        void rejectsSellerOrderWithoutItems() {
            long orderId = insertOrder("20260809-4F2K98", 0L, 0L, 0L, 0L);
            insertSellerOrder(orderId, sellerId, 0L);

            assertThatThrownBy(OrderSchemaTest.this::flush)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("배송지는")
    class Shipping {

        @Test
        @DisplayName("주문과 다른 테이블에 있다")
        void livesInItsOwnTable() {
            assertThat(columnsOf("shop_order"))
                    .as("주문에 사람 정보를 박제하지 않는다(D13) — 5년 남길 것과 지울 것이 한 행에서 엉킨다")
                    .doesNotContain("receiver_name", "receiver_phone", "address1", "postal_code");
        }

        @Test
        @DisplayName("지워도 주문은 남는다")
        void isErasableWithoutTouchingTheOrder() {
            long orderId = aCompleteOrder("20260809-7QX4M4");
            insertShipping(orderId);

            jdbc.sql("delete from order_shipping where order_id = :id").param("id", orderId).update();
            flush();

            assertThat(exists("select 1 from shop_order where order_id = " + orderId))
                    .as("거래 사실은 5년 남기고 배송지는 먼저 파기한다(R6·R9 충돌 지점)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("거래기록은")
    class TransactionRecord {

        @Test
        @DisplayName("지울 컬럼이 없다")
        void hasNoSoftDeleteColumn() {
            assertThat(columnsOf("shop_order"))
                    .as("5년 보존을 약속이 아니라 구조로 지킨다(D23 「가장 낮은 층에 건다」)")
                    .doesNotContain("deleted_at");
        }

        @Test
        @DisplayName("주문에 쓰인 SKU 는 못 지운다")
        void pinsTheSku() {
            aCompleteOrder("20260809-7QX4M5");
            flush();

            assertThatThrownBy(() -> jdbc.sql("delete from sku where sku_id = :id")
                            .param("id", skuId)
                            .update())
                    .as("어떤 SKU 였나를 끝까지 따라갈 수 있어야 한다. 파는 것을 멈추려면 상태를 내린다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    /** 밀려 있던 지연 검사를 이 자리에서 돌린다. 커밋을 안 하는 테스트가 트리거를 보는 유일한 방법이다 */
    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    /** 합계까지 맞아떨어지는 주문. {@link #flush()} 를 지나야 하는 테스트가 쓴다 */
    private long aCompleteOrder(String number) {
        long orderId = insertOrder(number, 10_000L, 1_000L, 0L, 10_000L);
        long sellerOrderId = insertSellerOrder(orderId, sellerId, 0L);
        insertItem(sellerOrderId, 10_000L, 1, 1000);
        return orderId;
    }

    /**
     * 항목만 보는 테스트가 쓴다. 합계가 안 맞아서 {@link #flush()} 를 지나면 트리거에 걸린다 —
     * 그쪽 검증은 {@code Totals} 가 따로 한다.
     */
    private long anOrderWithSellerOrder() {
        return insertSellerOrder(insertOrder("20260809-7QX4M9", 0L, 0L, 0L, 0L), sellerId, 0L);
    }

    private long insertOrder(String number, long total, long commission, long shipping, long payable) {
        return jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, :total, :commission, :shipping, :payable)
                        returning order_id
                        """)
                .param("number", number)
                .param("userId", userId)
                .param("total", total)
                .param("commission", commission)
                .param("shipping", shipping)
                .param("payable", payable)
                .query(Long.class)
                .single();
    }

    private long insertSellerOrder(long orderId, long seller, long shippingFee) {
        return jdbc.sql("""
                        insert into seller_order (order_id, seller_id, shipping_fee)
                        values (:orderId, :sellerId, :fee)
                        returning seller_order_id
                        """)
                .param("orderId", orderId)
                .param("sellerId", seller)
                .param("fee", shippingFee)
                .query(Long.class)
                .single();
    }

    /** 등식을 지켜서 넣는다. 틀린 값을 넣는 것은 {@link #insertItemRaw} 다 */
    private void insertItem(long sellerOrderId, long unitPrice, int quantity, int commissionBp) {
        long lineAmount = unitPrice * quantity;
        insertItemRaw(sellerOrderId, unitPrice, quantity, lineAmount, commissionBp,
                lineAmount * commissionBp / 10_000);
    }

    private void insertItemRaw(long sellerOrderId, long unitPrice, int quantity, long lineAmount,
            int commissionBp, long commissionAmount) {
        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '테스트 상품',
                                :unitPrice, :quantity, :lineAmount, :bp, :commission)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .param("unitPrice", unitPrice)
                .param("quantity", quantity)
                .param("lineAmount", lineAmount)
                .param("bp", commissionBp)
                .param("commission", commissionAmount)
                .update();
    }

    private void insertShipping(long orderId) {
        jdbc.sql("""
                        insert into order_shipping (order_id, receiver_name, receiver_phone,
                                                    postal_code, address1)
                        values (:orderId, '홍길동', '010-0000-0000', '06134', '서울시 강남구')
                        """)
                .param("orderId", orderId)
                .update();
    }

    private long insertSku(long seller, long price) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", seller)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price, stock_count)
                        values (:productId, :price, 100)
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", price)
                .query(Long.class)
                .single();
    }

    private long commissionTotalOf(long orderId) {
        return jdbc.sql("""
                        select coalesce(sum(oi.commission_amount), 0)
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private java.util.List<String> columnsOf(String table) {
        return jdbc.sql("""
                        select column_name from information_schema.columns
                         where table_schema = 'public' and table_name = :table
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }

    private boolean exists(String sql) {
        return Boolean.TRUE.equals(jdbc.sql("select exists(" + sql + ")").query(Boolean.class).single());
    }
}
