package com.projectshop.shop.order;

import org.springframework.stereotype.Component;

import com.projectshop.shop.payment.PaymentOutcome;

/**
 * 결제 결과를 받아 주문 상태를 옮긴다.
 *
 * <p><b>결제가 주문을 부르던 화살표를 뒤집은 자리다.</b> {@link PaymentOutcome} 은 결제 패키지가
 * 들고 있고 구현이 여기 있어서, 남는 방향은 주문 → 결제 하나다(`D23` 「의존」).
 *
 * <p><b>여기서 전이를 고르지 않는다.</b> 무엇에서 무엇으로 갈 수 있나는
 * {@link OrderStatusService} 의 표 하나가 든다.
 */
@Component
class PaymentOutcomeHandler implements PaymentOutcome {

    private final OrderStatusService orderStatuses;

    PaymentOutcomeHandler(OrderStatusService orderStatuses) {
        this.orderStatuses = orderStatuses;
    }

    @Override
    public void paid(long orderId, String reason) {
        orderStatuses.markPaid(orderId, reason);
    }

    @Override
    public void failed(long orderId, String reason) {
        orderStatuses.markPaymentFailed(orderId, reason);
    }
}
