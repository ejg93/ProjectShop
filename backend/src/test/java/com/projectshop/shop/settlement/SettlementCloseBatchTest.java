package com.projectshop.shop.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 정산 마감 배치가 `D19` 3층(체인)을 실제로 도는지, 그리고 두 번 돌아도 지급이 두 배가
 * 안 되는지 본다.
 *
 * <p><b>체인은 규칙만 있고 코드가 없던 자리다.</b> 그 문서가 「체인이 하나라도 생기면 3층이
 * 실제로 도는지 테스트로 고정한다」고 적어 뒀다 — 선행이 실패한 날 후행이 스킵되는 것이
 * 로그로만 확인되면 다음 사람이 그 규칙을 못 본다.
 *
 * <p><b>선행 회차를 손으로 넣는다.</b> 자동 구매확정을 실제로 돌리면 이 테스트가 그쪽 규칙까지
 * 지게 된다 — 여기서 필요한 것은 {@code batch_run} 에 그 행이 있나뿐이다.
 */
@DisplayName("정산 마감 배치")
class SettlementCloseBatchTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int COMMISSION_BP = 1000;
    private static final long COMMISSION = PRICE * COMMISSION_BP / 10_000;
    private static final long SHIPPING_FEE = 3_000;

    /** 대상 기간의 마지막 날. 기준일이 곧 이 값이다 */
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Autowired
    private SettlementCloseBatch batch;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long sellerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("close-buyer@test.local", "산사람");
        sellerId = fixture.insertSeller("s-close", "마감셀러");
        skuId = insertSku();
    }

    @Nested
    @DisplayName("체인은")
    class Chain {

        @Test
        @DisplayName("선행이 그날 성공하지 않았으면 안 돈다")
        void skipsWhenThePrerequisiteDidNotSucceed() {
            confirmedOrder(PERIOD_END);

            assertThat(batch.close(PERIOD_END))
                    .as("확정 안 된 건이 정산에서 빠지는데 정산서는 그것대로 합이 맞는다")
                    .isEmpty();
            assertThat(settlementCount()).isZero();
        }

        @Test
        @DisplayName("안 돌면 스킵을 남긴다")
        void leavesASkippedRun() {
            confirmedOrder(PERIOD_END);
            batch.close(PERIOD_END);

            assertThat(runStatus())
                    .as("아무것도 안 남기면 「선행이 막았다」와 「스케줄이 안 걸렸다」가 안 갈린다")
                    .isEqualTo("skipped");
        }

        @Test
        @DisplayName("선행이 성공해 있으면 돈다")
        void runsWhenThePrerequisiteSucceeded() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);

            assertThat(batch.close(PERIOD_END)).isPresent();
            assertThat(settlementCount()).isEqualTo(1);
        }

        /**
         * 선행은 매일 돌고 후행은 월 1회다. 기준일을 실행일로 잡으면 <b>말일 회차를 못 보고</b>
         * 1일 회차만 보게 된다 — 그 말일이 이번 정산의 마지막 확정분이 나온 날이다.
         */
        @Test
        @DisplayName("다른 날의 선행 성공은 안 본다")
        void ignoresAPrerequisiteFromAnotherDay() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END.plusDays(1));

            assertThat(batch.close(PERIOD_END)).isEmpty();
        }
    }

    @Nested
    @DisplayName("정산서는")
    class Statements {

        @Test
        @DisplayName("대금에서 수수료를 빼고 배송비를 더한다")
        void addsSalesAndShippingAndSubtractsCommission() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);

            batch.close(PERIOD_END);

            assertThat(payoutAmount())
                    .as("수수료를 정산 때 다시 계산하지 않는다 — 주문 시점 박제값을 읽는다")
                    .isEqualTo(PRICE - COMMISSION + SHIPPING_FEE);
        }

        @Test
        @DisplayName("지급일이 다음 달 10일이고 쉬는 날이면 밀린다")
        void freezesThePayoutDate() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);

            batch.close(PERIOD_END);

            // 2026-08-10 은 월요일이다. 휴일표에 없으면 그대로다.
            assertThat(payoutDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        }

        @Test
        @DisplayName("구매확정이 아니면 안 담는다")
        void ignoresBundlesThatAreNotConfirmed() {
            long sellerOrderId = confirmedOrder(PERIOD_END);
            jdbc.sql("update seller_order set status = 'shipping' where seller_order_id = :id")
                    .param("id", sellerOrderId)
                    .update();
            prerequisiteSucceeded(PERIOD_END);

            batch.close(PERIOD_END);

            assertThat(settlementCount())
                    .as("배송 중이거나 반품 요청된 건은 대상이 아니다")
                    .isZero();
        }

        @Test
        @DisplayName("다른 달에 확정된 건은 안 담는다")
        void ignoresOtherPeriods() {
            confirmedOrder(PERIOD_END.plusDays(1));
            prerequisiteSucceeded(PERIOD_END);

            batch.close(PERIOD_END);

            assertThat(settlementCount()).isZero();
        }
    }

    @Nested
    @DisplayName("두 번 돌아도")
    class Idempotency {

        @Test
        @DisplayName("같은 회차는 다시 안 돈다")
        void skipsARunThatAlreadySucceeded() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);
            batch.close(PERIOD_END);

            assertThat(batch.close(PERIOD_END))
                    .as("`batch_run` 부분 유니크가 지키는 것을 회차 판정이 먼저 본다")
                    .isEmpty();
            assertThat(settlementCount()).isEqualTo(1);
        }

        /**
         * <b>합계 불일치로는 안 잡히는 사고다.</b> 같은 주문 항목이 다음 달 정산서에 다시 실리면
         * 두 정산서 각각은 그것대로 합이 맞고 셀러는 돈을 두 번 받는다.
         */
        @Test
        @DisplayName("다음 달 마감이 지난 달 건을 다시 안 담는다")
        void doesNotBillTheSameOrderItemTwice() {
            confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);
            batch.close(PERIOD_END);

            LocalDate august = LocalDate.of(2026, 8, 31);
            prerequisiteSucceeded(august);
            batch.close(august);

            assertThat(settlementCount())
                    .as("8월에는 담을 것이 없어서 정산서가 안 선다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("정산 후 환불은")
    class RefundAfterSettlement {

        @Test
        @DisplayName("다음 주기에서 회수되고 음수면 이월된다")
        void isRecoveredInTheNextCycleAndCarriesOver() {
            long sellerOrderId = confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);
            batch.close(PERIOD_END);

            long julyPayout = payoutAmount();

            // 8월에 승인된 환불이다. 7월 정산서에 이미 실린 항목을 되돌린다.
            approveRefund(sellerOrderId, LocalDate.of(2026, 8, 15));

            LocalDate august = LocalDate.of(2026, 8, 31);
            prerequisiteSucceeded(august);
            batch.close(august);

            assertThat(settlementCount()).isEqualTo(2);
            assertThat(payoutAmountFor(august))
                    .as("8월에는 판매가 없고 회수만 있다")
                    .isEqualTo(-PRICE + COMMISSION);
            assertThat(carriedOverFor(august))
                    .as("지급액이 음수면 그 값이 그대로 다음 달로 넘어간다")
                    .isEqualTo(-PRICE + COMMISSION);
            assertThat(julyPayout).isEqualTo(PRICE - COMMISSION + SHIPPING_FEE);
        }

        @Test
        @DisplayName("넘긴 잔액을 그다음 주기가 받는다")
        void isCarriedIntoTheFollowingCycle() {
            long sellerOrderId = confirmedOrder(PERIOD_END);
            prerequisiteSucceeded(PERIOD_END);
            batch.close(PERIOD_END);

            approveRefund(sellerOrderId, LocalDate.of(2026, 8, 15));
            LocalDate august = LocalDate.of(2026, 8, 31);
            prerequisiteSucceeded(august);
            batch.close(august);

            LocalDate september = LocalDate.of(2026, 9, 30);
            prerequisiteSucceeded(september);
            batch.close(september);

            assertThat(payoutAmountFor(september))
                    .as("거래가 없고 이월만 있는 셀러도 정산서가 선다 — 빼면 채권이 사라진다")
                    .isEqualTo(-PRICE + COMMISSION);
            assertThat(carryOverLineCount())
                    .as("한 정산서의 잔액은 한 번만 넘어간다")
                    .isEqualTo(1);
        }
    }

    private int settlementCount() {
        return jdbc.sql("select count(*) from settlement where seller_id = :id")
                .param("id", sellerId)
                .query(Integer.class)
                .single();
    }

    private long payoutAmount() {
        return jdbc.sql("select payout_amount from settlement where seller_id = :id")
                .param("id", sellerId)
                .query(Long.class)
                .single();
    }

    private long payoutAmountFor(LocalDate periodEnd) {
        return amountColumnFor("payout_amount", periodEnd);
    }

    private long carriedOverFor(LocalDate periodEnd) {
        return amountColumnFor("carried_over", periodEnd);
    }

    /**
     * 주기로 정산서를 집어 금액 칸 하나를 읽는다.
     *
     * <p>컬럼 이름을 SQL 텍스트에 끼우는 것이라 값이 아니다 — 부르는 자리가 전부 이 파일 안의
     * 리터럴이고 바깥에서 오는 값이 없다(`D23` 「SQL」).
     */
    private long amountColumnFor(String column, LocalDate periodEnd) {
        return jdbc.sql("""
                        select s.%s from settlement s
                          join settlement_cycle c on c.settlement_cycle_id = s.settlement_cycle_id
                         where s.seller_id = :id and c.period_end = :periodEnd
                        """.formatted(column))
                .param("id", sellerId)
                .param("periodEnd", periodEnd)
                .query(Long.class)
                .single();
    }

    private int carryOverLineCount() {
        return jdbc.sql("""
                        select count(*) from settlement_item i
                          join settlement s on s.settlement_id = i.settlement_id
                         where s.seller_id = :id and i.kind = 'carryover'
                        """)
                .param("id", sellerId)
                .query(Integer.class)
                .single();
    }

    private LocalDate payoutDate() {
        return jdbc.sql("""
                        select c.payout_date from settlement_cycle c
                          join settlement s on s.settlement_cycle_id = c.settlement_cycle_id
                         where s.seller_id = :id
                        """)
                .param("id", sellerId)
                .query(LocalDate.class)
                .single();
    }

    private String runStatus() {
        return jdbc.sql("""
                        select status from batch_run
                         where batch_name = 'settlement_close'
                         order by batch_run_id desc limit 1
                        """)
                .query(String.class)
                .single();
    }

    /** 선행 회차를 손으로 남긴다. 여기서 필요한 것은 그 행이 있나뿐이다 */
    private void prerequisiteSucceeded(LocalDate baselineDate) {
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status)
                        values ('auto_confirm', :baselineDate, now(), now(), 0, 0, 'succeeded')
                        """)
                .param("baselineDate", baselineDate)
                .update();
    }

    /**
     * 그 날짜에 구매확정된 묶음 하나. 항목 하나와 배송비를 갖는다.
     *
     * @return 만든 {@code seller_order_id}
     */
    private long confirmedOrder(LocalDate confirmedOn) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, :price, :commission, :shipping, :payable)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .param("price", PRICE)
                .param("commission", COMMISSION)
                .param("shipping", SHIPPING_FEE)
                .param("payable", PRICE + SHIPPING_FEE)
                .query(Long.class)
                .single();

        OrderFixture.attachContractDocuments(jdbc, orderId);

        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, status, closed_at)
                        values (:number, :orderId, :sellerId, :fee, 'confirmed', :closedAt)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .param("fee", SHIPPING_FEE)
                .param("closedAt", confirmedOn.atTime(12, 0)
                        .atZone(BusinessCalendar.ZONE).toOffsetDateTime())
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '마감 테스트 상품',
                                :price, 1, :price, :bp, :commission)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .param("price", PRICE)
                .param("bp", COMMISSION_BP)
                .param("commission", COMMISSION)
                .update();

        return sellerOrderId;
    }

    /**
     * 승인된 환불 하나를 손으로 넣는다.
     *
     * <p>{@code RefundService} 를 거치지 않는 이유는 그쪽이 묶음 상태를 {@code cancelled}
     * 이나 {@code returned} 로 요구해서다 — 여기서 보려는 것은 <b>구매확정 뒤에 난 환불</b>이고,
     * 그 경로는 {@code payment_error} 사유가 담당한다(`V25`).
     */
    private void approveRefund(long sellerOrderId, LocalDate decidedOn) {
        OffsetDateTime decidedAt = decidedOn.atTime(12, 0)
                .atZone(BusinessCalendar.ZONE).toOffsetDateTime();

        long refundId = jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, status, reason_code,
                                            amount, requested_by_type, requested_by_user_id,
                                            approved_by_type, approved_by_user_id,
                                            decided_at, due_at, gateway_refund_number)
                        values (:number, :sellerOrderId, 'approved', 'payment_error', :amount,
                                'system', null, 'system', null, :decidedAt, :decidedAt, 'GW-1')
                        returning refund_id
                        """)
                .param("number", "R-" + OrderFixture.sellerOrderNumber().substring(2))
                .param("sellerOrderId", sellerOrderId)
                .param("amount", PRICE)
                .param("decidedAt", decidedAt)
                .query(Long.class)
                .single();

        long orderItemId = jdbc.sql(
                        "select order_item_id from order_item where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into refund_item (refund_id, order_item_id, quantity,
                                                 amount, commission_refund)
                        values (:refundId, :orderItemId, 1, :amount, :commission)
                        """)
                .param("refundId", refundId)
                .param("orderItemId", orderItemId)
                .param("amount", PRICE)
                .param("commission", COMMISSION)
                .update();
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '마감 테스트 상품')
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
                        select sku_id, 100 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
