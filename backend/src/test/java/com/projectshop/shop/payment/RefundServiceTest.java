package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 환불이 요청과 승인으로 갈려 있나, 그리고 돈이 맞게 갈리나.
 *
 * <p><b>단가를 나눠떨어지지 않게 잡았다.</b> {@code 3,334 × 3 = 10,002} 이고 수수료는
 * 10%를 항목 단위로 잘라 {@code 1,000} 이다. 수량 하나씩 환불하면 {@code 1000/3 = 333} 이라
 * 잔액이 1원 뜨는데, <b>그 1원이 어디로 가는지가 이 청크가 정한 것</b>이다
 * (`money-invariants` 「통째로 환불하면 {@code commission_refund = commission_amount}」).
 * 나눠떨어지는 값으로 잡으면 그 규칙이 있으나 없으나 테스트가 통과한다.
 *
 * <p><b>지연 트리거는 커밋 시점에 돈다.</b> 테스트는 {@code @Transactional} 이라 커밋을 안 하므로
 * {@link #flush()} 가 {@code set constraints all immediate} 로 그 자리에서 밀린 검사를 돌린다
 * ({@code OrderSchemaTest} 와 같은 방식).
 */
@DisplayName("환불")
class RefundServiceTest extends PostgresTestBase {

    private static final int STOCK = 10;
    private static final int ORDERED = 3;

    /** 나눠떨어지지 않는 단가. 위 클래스 주석을 본다 */
    private static final long PRICE = 3_334;

    private static final long LINE_AMOUNT = PRICE * ORDERED;
    private static final long COMMISSION = 1_000;
    private static final long SHIPPING_FEE = 3_000;

    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private RefundService refunds;

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderService orderService;

    @Autowired
    private BusinessCalendar calendar;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long approverId;
    private long skuId;
    private long orderId;
    private long sellerOrderId;
    private String sellerOrderNumber;
    private long orderItemId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("refund-buyer@test.local", "환불구매자");
        fixture.grantGlobal(buyerId, "customer");

        // 승인은 관리자만 한다(`V24`). 근거가 법이다 — 대금을 받은 우리가 환급 의무자고
        // 중개자 고지로 그 책임을 못 면한다(`D2` R5, 전자상거래법 제18조제2항·제20조의2제3항).
        approverId = fixture.insertUser("refund-approver@test.local", "환불승인자");
        fixture.grantGlobal(approverId, "admin");

        long sellerId = fixture.insertSeller("s-refund", "환불셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku(sellerId);

        OrderService.Created created = placeOrder();
        orderId = created.orderId();

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), "card", GOOD_CARD));

        readBundle();
    }

    @Nested
    @DisplayName("요청하면")
    class Requested {

        @Test
        @DisplayName("남은 것 전부와 배송비를 잡는다")
        void takesEverythingLeft() {
            closeBundle("cancelled");

            RefundService.Refund refund = requestAll();

            assertThat(refund.amount())
                    .as("항목을 안 주면 남은 것 전부다. 화면이 세어 보내게 하면 그 계산이 두 곳에 생긴다")
                    .isEqualTo(LINE_AMOUNT + SHIPPING_FEE);
            assertThat(refund.shippingFeeRefund()).isEqualTo(SHIPPING_FEE);
            assertThat(refund.status()).isEqualTo("REQUESTED");
        }

        @Test
        @DisplayName("돈은 아직 안 나간다")
        void movesNoMoneyYet() {
            closeBundle("cancelled");

            RefundService.Refund refund = requestAll();

            assertThat(refund.gatewayRefundNumber())
                    .as("요청과 승인을 가른 이유가 이것이다. 요청만으로 나가면 가를 것이 없다")
                    .isNull();
        }

        /**
         * 기한을 <b>묶음이 닫힌 날</b>에서 센다(`D2` R5).
         *
         * <p>요청 시각에서 세면 늦게 요청할수록 기한이 밀려서, 법이 정한 것보다 늦게 줘도
         * 안 늦은 것이 된다.
         */
        @Test
        @DisplayName("환급 기한이 3영업일로 박제된다")
        void freezesTheRefundDeadline() {
            closeBundle("cancelled");

            RefundService.Refund refund = requestAll();

            LocalDate expected = calendar.plusBusinessDays(LocalDate.now(BusinessCalendar.ZONE), 3);

            assertThat(refund.dueAt().atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate())
                    .as("저장하고 되읽어도 같은 날이어야 한다 — 마이크로초 올림이 하루를 민다(`stack.md`)")
                    .isEqualTo(expected);
        }
    }

    /**
     * <b>법이 사유마다 다른 기산점을 정한다</b>(`D2` R5).
     *
     * <p>같은 「취소」인데 조문이 갈린다 — 고객이 무르면 청약철회라 제18조제2항 3호고,
     * 셀러가 못 보내면 공급 곤란이라 <b>제15조제2항</b>이다. 뒤엣것은 <b>대금을 지급한 날</b>부터
     * 센다. 취소 시각에서 세면 결제일보다 뒤로 밀려서 <b>법보다 늦게 잡는다.</b>
     *
     * <p>결제는 {@code setUp} 에서 지금 일어나고, 이 테스트들이 <b>취소 시각을 닷새 뒤로 민다</b> —
     * 두 기산점이 같은 날이면 무엇을 쓰든 통과해서 규칙이 있으나 없으나 초록이 된다.
     */
    @Nested
    @DisplayName("환급 기한의 기산점은")
    class DueDateBasis {

        /** 결제일과 취소일이 갈리게 미는 날수. 3영업일 계산이 겹치지 않을 만큼 크다 */
        private static final int CLOSED_LATER_DAYS = 5;

        @Test
        @DisplayName("고객 취소면 청약철회한 날이다")
        void countsFromClosedAtWhenCustomerCancels() {
            closeBundleLate("cancelled");

            RefundService.Refund refund = requestAll();

            assertThat(dueDateOf(refund))
                    .as("제18조제2항 3호 — 재화등을 공급하지 아니한 청약철회는 철회한 날부터 센다")
                    .isEqualTo(businessDaysAfter(today().plusDays(CLOSED_LATER_DAYS)));
        }

        @Test
        @DisplayName("셀러 공급 불능이면 대금을 지급한 날이다")
        void countsFromPaymentWhenSupplyFails() {
            closeBundleLate("cancelled");

            RefundService.Refund refund = request("supply_failed", ORDERED);

            assertThat(dueDateOf(refund))
                    .as("제15조제2항 — 청약철회가 아니라 공급 곤란이라 대금 지급일부터 센다")
                    .isEqualTo(businessDaysAfter(today()));
        }

        /**
         * 관리자 취소는 사유가 자유 텍스트라 코드가 조문을 못 고른다.
         *
         * <p><b>모르면 이른 쪽</b>이다 — 늦게 잡으면 위반이고 일찍 잡으면 우리가 손해를 볼 뿐이다.
         */
        @Test
        @DisplayName("관리자 취소면 이른 쪽인 결제일이다")
        void countsFromPaymentWhenAdminCancels() {
            closeBundleLate("cancelled");

            RefundService.Refund refund = request("admin_cancelled", ORDERED);

            assertThat(dueDateOf(refund)).isEqualTo(businessDaysAfter(today()));
        }

        @Test
        @DisplayName("반품이면 재화를 반환받은 날이다")
        void countsFromClosedAtWhenReturned() {
            closeBundleLate("returned");

            RefundService.Refund refund = request("withdrawal", ORDERED);

            assertThat(dueDateOf(refund))
                    .as("제18조제2항 1호 — 셀러가 반품완료를 누르는 것이 반환 수령이다")
                    .isEqualTo(businessDaysAfter(today().plusDays(CLOSED_LATER_DAYS)));
        }

        /** 결제보다 늦게 닫는다. 두 기산점이 갈려야 어느 쪽을 썼는지가 드러난다 */
        private void closeBundleLate(String status) {
            jdbc.sql("""
                            update seller_order
                               set status = :status, closed_at = now() + make_interval(days => :days)
                             where order_id = :orderId
                            """)
                    .param("status", status)
                    .param("days", CLOSED_LATER_DAYS)
                    .param("orderId", orderId)
                    .update();
        }
    }

    @Nested
    @DisplayName("부분 환불은")
    class Partial {

        @Test
        @DisplayName("배송비를 안 준다")
        void withholdsTheShippingFee() {
            closeBundle("returned");

            RefundService.Refund refund = request("withdrawal", 1);

            assertThat(refund.shippingFeeRefund())
                    .as("배송비는 묶음 단위라 항목별로 나눌 근거가 없다")
                    .isZero();
            assertThat(refund.amount()).isEqualTo(PRICE);
        }

        /**
         * 셋을 하나씩 돌려주면 수수료가 {@code 333 + 333 + 334} 다.
         *
         * <p>마지막에 안 몰아 주면 999 만 돌아가고 <b>1원이 정산에 우리 몫으로 남는다.</b>
         * 통째로 한 번에 돌려준 것과 값이 달라지는 것도 같은 실수다.
         */
        @Test
        @DisplayName("수수료 절사 잔액이 마지막 수량에 몰린다")
        void putsTheRoundingRemainderOnTheLastUnit() {
            closeBundle("returned");

            long first = commissionOf(request("withdrawal", 1));
            long second = commissionOf(request("withdrawal", 1));
            long third = commissionOf(request("withdrawal", 1));

            assertThat(List.of(first, second, third)).containsExactly(333L, 333L, 334L);
            assertThat(first + second + third)
                    .as("통째로 환불하면 commission_refund 합이 commission_amount 와 같다(`money-invariants`)")
                    .isEqualTo(COMMISSION);
        }

        @Test
        @DisplayName("전부 돌려주고 나면 배송비가 그때 나간다")
        void releasesTheShippingFeeWhenTheBundleEmpties() {
            closeBundle("returned");

            request("withdrawal", 2);
            RefundService.Refund last = request("withdrawal", 1);

            assertThat(last.shippingFeeRefund()).isEqualTo(SHIPPING_FEE);
            assertThat(totalRefunded())
                    .as("나눠 환불해도 합은 통째 환불과 같아야 한다")
                    .isEqualTo(LINE_AMOUNT + SHIPPING_FEE);
        }

        @Test
        @DisplayName("남은 수량보다 많이 못 잡는다")
        void cannotExceedTheRemainingQuantity() {
            closeBundle("returned");
            request("withdrawal", 2);

            assertThatThrownBy(() -> request("withdrawal", 2))
                    .as("환불이 여러 번 나므로 한 행만 봐서는 못 막는다. 누계로 센다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_LIMIT));
        }
    }

    @Nested
    @DisplayName("승인은")
    class Approval {

        /**
         * <b>승인 권한을 가진 사람이 자기 요청을 승인하는 것</b>이 이 제약이 막는 유일한 조합이다.
         *
         * <p>고객으로는 이 자리를 못 만든다 — 애초에 승인 권한이 없어서 판정에서 먼저 걸린다.
         * 관리자가 자기 이름으로 요청을 내는 것은 실제로 있는 경로다(`payment:request_refund` 가
         * 관리자에게 {@code all} 스코프로 있다).
         */
        @Test
        @DisplayName("승인 권한자도 자기가 낸 요청은 못 한다")
        void cannotBeDoneByTheRequester() {
            closeBundle("cancelled");

            RefundService.Refund refund = refunds.request(approverId,
                    new RefundService.RequestCommand(sellerOrderNumber, "cancelled", List.of(), null));

            assertThatThrownBy(() -> refunds.approve(approverId, refund.refundNumber(), "확인함"))
                    .as("요청과 승인을 가른 것이 형식만 남으면 가른 값이 없다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_SELF_APPROVAL));
        }

        /**
         * 고객에게는 <b>없는 요청과 같은 404</b> 다(`D5` 「권한 실패」).
         *
         * <p>403 을 주면 번호를 두드려서 환불이 몇 건인지 셀 수 있고, 그게 곧 취소율이다.
         */
        @Test
        @DisplayName("요청만 낼 수 있는 사람에게는 없는 것과 같다")
        void looksMissingToRequestOnlyRoles() {
            closeBundle("cancelled");
            RefundService.Refund refund = requestAll();

            assertThatThrownBy(() -> refunds.approve(buyerId, refund.refundNumber(), "확인함"))
                    .as("권한을 둘로 가른 값이 여기서 나온다(`V24`)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_NOT_FOUND));
        }

        @Test
        @DisplayName("셀러도 승인은 못 한다")
        void isClosedToSellers() {
            long sellerOwner = fixture.insertUser("refund-seller-owner@test.local", "환불셀러대표");
            fixture.joinSeller(sellerIdOfBundle(), sellerOwner);
            fixture.grantOrg(sellerOwner, "seller_owner", sellerIdOfBundle());

            closeBundle("cancelled");
            RefundService.Refund refund = requestAll();

            assertThatThrownBy(() -> refunds.approve(sellerOwner, refund.refundNumber(), "확인함"))
                    .as("대금을 받은 우리가 환급 의무자다(`D2` R5, 제18조제2항·제20조의2제3항). "
                            + "셀러가 승인하면 우리 법적 의무의 이행 여부를 남이 정한다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_NOT_FOUND));
        }

        @Test
        @DisplayName("PG 거래번호를 남긴다")
        void recordsTheGatewayTransaction() {
            closeBundle("cancelled");
            RefundService.Refund requested = requestAll();

            RefundService.Refund approved =
                    refunds.approve(approverId, requested.refundNumber(), "확인함");

            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.gatewayRefundNumber())
                    .as("없으면 돈이 나갔는지를 우리 기록만 보고는 못 가린다")
                    .isNotBlank();
        }

        @Test
        @DisplayName("두 번은 안 된다")
        void happensOnlyOnce() {
            closeBundle("cancelled");
            RefundService.Refund requested = requestAll();
            refunds.approve(approverId, requested.refundNumber(), "확인함");

            assertThatThrownBy(() -> refunds.approve(approverId, requested.refundNumber(), "또"))
                    .as("조건부 UPDATE 라 동시에 둘이 와도 하나만 통과한다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_ALREADY_DECIDED));
        }
    }

    @Nested
    @DisplayName("반려는")
    class Rejection {

        @Test
        @DisplayName("사유가 없으면 안 된다")
        void needsAReason() {
            closeBundle("cancelled");
            RefundService.Refund refund = requestAll();

            assertThatThrownBy(() -> refunds.reject(approverId, refund.refundNumber(), "  "))
                    .as("돈이 안 나가는 결정이라 왜 그랬는지가 없으면 고객에게 답할 말이 없다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.TRANSITION_REASON_REQUIRED));
        }

        @Test
        @DisplayName("반려된 수량은 다시 환불할 수 있다")
        void putsTheQuantityBack() {
            closeBundle("cancelled");
            RefundService.Refund first = requestAll();
            refunds.reject(approverId, first.refundNumber(), "증빙이 없다");

            RefundService.Refund again = requestAll();

            assertThat(again.amount())
                    .as("반려는 누계에서 빠진다. 안 빠지면 한 번 반려된 묶음은 영영 환불이 막힌다")
                    .isEqualTo(LINE_AMOUNT + SHIPPING_FEE);
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Blocks {

        @Test
        @DisplayName("결제 승인이 없으면 환불도 없다")
        void requiresAnApprovedPayment() {
            long otherOrderId = anotherUnpaidOrder();
            String otherBundle = bundleNumberOf(otherOrderId);
            closeBundleOf(otherOrderId, "cancelled");

            assertThatThrownBy(() -> refunds.request(buyerId,
                    new RefundService.RequestCommand(otherBundle, "cancelled", List.of(), null)))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_NOT_PAYABLE));
        }

        @Test
        @DisplayName("사유와 묶음 상태가 안 맞으면 거부한다")
        void requiresTheMatchingBundleStatus() {
            assertThatThrownBy(() -> requestAll())
                    .as("배송 중인 묶음에 취소 환불이 붙으면 안 된다. 지금은 preparing 이다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED));
        }

        /**
         * {@code payment_error} 만 상태를 안 본다.
         *
         * <p>그 사유 자체가 <b>상태와 결제가 어긋난 것을 고치는 것</b>이라, 맞는 상태를 요구하면
         * {@code PaymentService.findPayable} 이 주석으로 남긴 구멍을 못 닫는다.
         */
        @Test
        @DisplayName("결제 오류 정정은 상태를 안 본다")
        void letsPaymentErrorThroughAnyStatus() {
            RefundService.Refund refund = refunds.request(buyerId,
                    new RefundService.RequestCommand(sellerOrderNumber, "payment_error",
                            List.of(), "승인은 났는데 주문이 만료됐다"));

            assertThat(refund.amount()).isEqualTo(LINE_AMOUNT + SHIPPING_FEE);
        }

        @Test
        @DisplayName("결제 없는 주문의 환불을 트리거가 막는다")
        void isBlockedByTheTriggerWhenNothingWasPaid() {
            long otherOrderId = anotherUnpaidOrder();
            long otherBundleId = bundleIdOf(otherOrderId);

            insertRefundRow(otherBundleId, LINE_AMOUNT, itemIdOf(otherBundleId), ORDERED);

            assertThatThrownBy(RefundServiceTest.this::flush)
                    .as("앱이 먼저 막지만 그건 강제 지점 3위라 새 입구가 생기면 빠뜨린다(`D23` 축 2)")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("주문 수량을 넘는 환불을 트리거가 막는다")
        void isBlockedByTheTriggerWhenQuantityExceeds() {
            closeBundle("cancelled");

            insertRefundRow(sellerOrderId, LINE_AMOUNT, orderItemId, ORDERED + 1);

            assertThatThrownBy(RefundServiceTest.this::flush)
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("자기승인을 제약이 막는다")
        void isBlockedByTheConstraintOnSelfApproval() {
            closeBundle("cancelled");
            RefundService.Refund refund = requestAll();

            assertThatThrownBy(() -> jdbc.sql("""
                            update refund
                               set status = 'approved', approved_by_type = 'admin',
                                   approved_by_user_id = requested_by_user_id,
                                   decided_at = now(), gateway_refund_number = 'MR-forced'
                             where refund_number = :number
                            """)
                    .param("number", refund.refundNumber())
                    .update())
                    .as("앱만 있으면 psql 로 넣는 경로가 그대로 통과한다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * 앱을 안 거치고 넣을 때 쓰는 번호.
     *
     * <p>형식에 {@code check} 가 걸려 있고 유니크라 상수를 박으면 두 번째 호출에서 걸린다.
     * 여기 필요한 것은 예측 불가능성이 아니라 한 실행 안에서 안 겹치는 것뿐이다
     * ({@code OrderFixture} 와 같은 판단).
     */
    private static String forcedRefundNumber() {
        return "R-20260101-" + "%06d".formatted(FORCED_SEQUENCE.incrementAndGet())
                .replace('0', 'A').replace('1', 'B');
    }

    private static final AtomicInteger FORCED_SEQUENCE = new AtomicInteger();

    /** 밀려 있던 지연 검사를 이 자리에서 돌린다. 커밋을 안 하는 테스트가 트리거를 보는 유일한 방법이다 */
    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    private RefundService.Refund requestAll() {
        return refunds.request(buyerId,
                new RefundService.RequestCommand(sellerOrderNumber, "cancelled", List.of(), null));
    }

    private RefundService.Refund request(String reasonCode, int quantity) {
        return refunds.request(buyerId, new RefundService.RequestCommand(sellerOrderNumber,
                reasonCode, List.of(new RefundService.Line(orderItemId, quantity)), null));
    }

    /** 저장된 기한을 날짜로 되돌린다. 왕복이 하루를 밀지 않는지도 같이 본다(`stack.md`) */
    private static LocalDate dueDateOf(RefundService.Refund refund) {
        return refund.dueAt().atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();
    }

    /** 그날 다음날부터 3영업일째. 서비스가 쓰는 계산과 같은 것을 쓴다 */
    private LocalDate businessDaysAfter(LocalDate from) {
        return calendar.plusBusinessDays(from, 3);
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessCalendar.ZONE);
    }

    private long commissionOf(RefundService.Refund refund) {
        return jdbc.sql("""
                        select ri.commission_refund
                          from refund_item ri
                          join refund r on r.refund_id = ri.refund_id
                         where r.refund_number = :number
                        """)
                .param("number", refund.refundNumber())
                .query(Long.class)
                .single();
    }

    private long totalRefunded() {
        return jdbc.sql("""
                        select coalesce(sum(amount), 0) from refund
                         where seller_order_id = :id and status <> 'rejected'
                        """)
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();
    }

    /** 상태를 SQL 로 민다. 전이 자체는 `11-2` 가 보는 것이고 여기서는 환불이 읽는 값만 필요하다 */
    private void closeBundle(String status) {
        closeBundleOf(orderId, status);
    }

    private void closeBundleOf(long targetOrderId, String status) {
        jdbc.sql("""
                        update seller_order set status = :status, closed_at = now()
                         where order_id = :orderId
                        """)
                .param("status", status)
                .param("orderId", targetOrderId)
                .update();
    }

    private void insertRefundRow(long bundleId, long amount, long itemId, int quantity) {
        long refundId = jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, status, reason_code,
                                            amount, requested_by_type, requested_by_user_id, due_at)
                        values (:number, :bundleId, 'requested', 'cancelled', :amount,
                                'customer', :userId, now())
                        returning refund_id
                        """)
                .param("number", forcedRefundNumber())
                .param("bundleId", bundleId)
                .param("amount", amount)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into refund_item (refund_id, order_item_id, quantity,
                                                 amount, commission_refund)
                        values (:refundId, :itemId, :quantity, :amount, 0)
                        """)
                .param("refundId", refundId)
                .param("itemId", itemId)
                .param("quantity", quantity)
                .param("amount", amount)
                .update();
    }

    private void readBundle() {
        sellerOrderId = bundleIdOf(orderId);
        sellerOrderNumber = bundleNumberOf(orderId);
        orderItemId = itemIdOf(sellerOrderId);
    }

    private long sellerIdOfBundle() {
        return jdbc.sql("select seller_id from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();
    }

    private long bundleIdOf(long targetOrderId) {
        return jdbc.sql("select seller_order_id from seller_order where order_id = :orderId")
                .param("orderId", targetOrderId)
                .query(Long.class)
                .single();
    }

    private String bundleNumberOf(long targetOrderId) {
        return jdbc.sql("select seller_order_number from seller_order where order_id = :orderId")
                .param("orderId", targetOrderId)
                .query(String.class)
                .single();
    }

    private long itemIdOf(long bundleId) {
        return jdbc.sql("select order_item_id from order_item where seller_order_id = :id")
                .param("id", bundleId)
                .query(Long.class)
                .single();
    }

    private long anotherUnpaidOrder() {
        return placeOrder().orderId();
    }

    private OrderService.Created placeOrder() {
        // 주문을 두 번 세우는 테스트가 있어서 장바구니를 다시 만들지 않는다.
        // cart 의 유니크가 부분 인덱스라 on conflict 로는 대상 추론이 안 된다(`V15`).
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                                insert into cart (user_id) values (:userId) returning cart_id
                                """)
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
                .param("quantity", ORDERED)
                .query(Long.class)
                .single();

        return orderService.create(buyerId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                        "서울시 강남구", "101호", null)));
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '환불 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
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
}
