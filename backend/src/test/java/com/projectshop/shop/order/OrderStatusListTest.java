package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.support.ConstraintValues;

/**
 * 주문 축 상태 목록이 <b>사본 여섯</b>이다. 어긋나는 것을 여기서 잡는다
 * (`D23` 「목록이 둘로 갈리는 것을 테스트가 막는다」).
 *
 * <pre>
 * 결제  Payment enum   shop_order_status_check      이력 제약의 결제 갈래
 * 배송  Shipment enum  seller_order_status_check    이력 제약의 배송 갈래
 * </pre>
 *
 * <p>상품 축은 `7e` 가 이 방벽을 세웠는데 주문 축에는 없었다. 지금은 안 갈렸지만
 * <b>갈리는 것을 막는 것이 아무것도 없는 상태</b>였다 — 그것이 이 청크(`11-5`)의 이유다.
 */
@DisplayName("주문 축 상태 목록")
class OrderStatusListTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Nested
    @DisplayName("층마다의 제약")
    class PerLayer {

        @Test
        @DisplayName("결제 상태가 DB 제약과 같다")
        void paymentMatchesConstraint() {
            assertThat(ConstraintValues.of(jdbc, "shop_order_status_check"))
                    .as("`check` 와 `Payment` 가 갈리면 한쪽에만 있는 상태가 생긴다")
                    .containsExactlyInAnyOrderElementsOf(paymentCodes());
        }

        @Test
        @DisplayName("배송 상태가 DB 제약과 같다")
        void shipmentMatchesConstraint() {
            assertThat(ConstraintValues.of(jdbc, "seller_order_status_check"))
                    .containsExactlyInAnyOrderElementsOf(shipmentCodes());
        }

        /**
         * 두 목록이 안 겹친다. <b>그래서 타입을 갈랐다</b> —
         * 하나로 두면 {@code seller_order} 자리에 {@code PAID} 를 넣어도 컴파일이 통과한다.
         */
        @Test
        @DisplayName("결제 상태와 배송 상태는 겹치지 않는다")
        void twoListsDoNotOverlap() {
            assertThat(paymentCodes())
                    .as("겹치면 이력 한 줄만 보고 어느 층의 전이인지 못 가른다")
                    .doesNotContainAnyElementsOf(shipmentCodes());
        }
    }

    /**
     * 이력 제약은 <b>한 테이블에 두 층을 받아서</b> {@code case} 로 목록을 갈라 적는다.
     * 그래서 사본이 여기서 둘 더 늘고, 갈래마다 따로 대조해야 한다.
     *
     * <p>합집합만 보면 <b>배송 값이 결제 갈래에 섞여 들어간 것을 못 잡는다</b> —
     * 두 목록을 합치면 어차피 같은 집합이라 통과한다. 그러면 결제 이력에 {@code shipping} 이
     * 들어가도 DB 가 안 막고, 「어느 층의 전이인가」를 이력만 보고 못 가르게 된다.
     */
    @Nested
    @DisplayName("이력 제약의 두 갈래")
    class HistoryBranches {

        private static final String CONSTRAINT = "order_status_history_status_check";

        @Test
        @DisplayName("결제 갈래가 Payment 와 같다")
        void paymentBranchMatchesPayment() {
            assertThat(ConstraintValues.valuesIn(branch(0)))
                    .as("주문 층(`order_id`)의 이력은 결제 상태만 받아야 한다")
                    .containsExactlyInAnyOrderElementsOf(paymentCodes());
        }

        @Test
        @DisplayName("배송 갈래가 Shipment 와 같다")
        void shipmentBranchMatchesShipment() {
            assertThat(ConstraintValues.valuesIn(branch(1)))
                    .containsExactlyInAnyOrderElementsOf(shipmentCodes());
        }

        /**
         * {@code CASE WHEN ... THEN <결제> ELSE <배송> END} 에서 한쪽을 떼어 낸다.
         *
         * <p>{@code ELSE} 로 자르는 것이 거칠지만, 대안이 <b>어느 값이 어느 갈래인지를
         * 테스트에 손으로 적는 것</b>이라 그러면 사본이 또 는다.
         *
         * @param index 0 이면 {@code THEN} 쪽(결제), 1 이면 {@code ELSE} 쪽(배송)
         */
        private String branch(int index) {
            String definition = ConstraintValues.definitionOf(jdbc, CONSTRAINT);

            String[] halves = definition.split("\\bELSE\\b");
            assertThat(halves)
                    .as("갈래가 둘이 아니면 제약 모양이 바뀐 것이라 이 테스트가 헛돈다")
                    .hasSize(2);
            return halves[index];
        }
    }

    @Nested
    @DisplayName("모르는 값")
    class Unknown {

        @Test
        @DisplayName("결제 상태는 조용히 통과하지 않는다")
        void unknownPaymentThrows() {
            assertThatThrownBy(() -> Payment.of("payed"))
                    .as("모르는 값을 통과시키면 그 뒤의 전이·판정이 전부 엉뚱한 답을 낸다")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("배송 상태는 조용히 통과하지 않는다")
        void unknownShipmentThrows() {
            assertThatThrownBy(() -> Shipment.of("delivering"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static List<String> paymentCodes() {
        return Arrays.stream(Payment.values()).map(Payment::code).toList();
    }

    private static List<String> shipmentCodes() {
        return Arrays.stream(Shipment.values()).map(Shipment::code).toList();
    }
}
