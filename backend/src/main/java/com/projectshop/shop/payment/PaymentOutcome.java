package com.projectshop.shop.payment;

/**
 * 결제 결과를 주문에 알린다.
 *
 * <p><b>결제가 주문 패키지를 안 부르게 하는 자리다.</b> 전에는 {@code PaymentService} 가
 * {@code OrderStatusService} 를 직접 불러서 {@code order ↔ payment} 가 순환이었다
 * (`D23` 「의존」). 인터페이스를 부르는 쪽에 두면 구현이 주문 쪽에 생기고
 * 방향이 <b>주문 → 결제</b> 하나로 남는다 — {@code auth.PasswordResetMailer} 와 같은 모양이다.
 *
 * <p><b>이벤트가 아니라 인터페이스인 이유는 동기여야 해서다.</b> 재전송이 재생 응답을 받을 때
 * 주문이 이미 결제완료여야 한다(`D11`). 나중에 처리하면 결제 응답 시점의 주문이
 * 아직 {@code payment_pending} 이라 재생값과 실제가 갈린다.
 *
 * <p><b>전이 대상을 안 받는다.</b> 승인·거절 둘만 열고 무엇에서 무엇으로 가는지는 주문이 정한다
 * (`D23` 「남의 자원 표를 언제 직접 만지나」).
 */
public interface PaymentOutcome {

    /**
     * 승인됐다. {@code reason} 은 상태 이력에 남길 사유 문구다.
     */
    void paid(long orderId, String reason);

    /**
     * 거절됐다. 그 주문의 셀러 주문이 같이 닫히고 재고가 돌아간다 — 그 곁가지는 구현이 안다.
     */
    void failed(long orderId, String reason);
}
