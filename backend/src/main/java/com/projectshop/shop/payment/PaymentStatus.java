package com.projectshop.shop.payment;

import java.util.Arrays;

/**
 * 결제 시도 한 건의 결과. 저장값은 {@code payment.status} 고 목록은
 * {@code payment_status_check} 다(`V22`).
 *
 * <p><b>주문의 결제 층 상태가 아니다.</b> {@code shop_order.status} 는
 * {@code payment_pending}·{@code paid} 처럼 <b>주문이 어디까지 왔나</b>를 말하고
 * ({@code OrderTransitions.Payment}), 이쪽은 <b>승인을 한 번 시도해서 어떻게 됐나</b>다.
 * 실패한 시도도 행으로 남으므로 둘은 개수부터 다르다 — 주문 하나에 결제 행이 여럿일 수 있다.
 *
 * <p><b>{@code RefundStatus.APPROVED} 와 글자가 같고 뜻이 다르다.</b> 문자열로 두면
 * 한쪽 값을 다른 쪽 자리에 넣어도 컴파일이 통과한다({@code ProductStatus} 와 {@code SkuStatus} 가
 * {@code on_sale} 로 같은 함정을 지고 있어서 타입으로 갈라 뒀다).
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 승인된 결제가 있는지를 정산·환불·스위퍼가 각각 조건으로 보고 있다.
 */
public enum PaymentStatus {

    /** 게이트웨이가 승인했다. 주문이 {@code paid} 로 가는 근거다 */
    APPROVED,

    /** 거절됐다. <b>행이 남는다</b> — 왜 거절됐는지를 고객에게 답해야 한다 */
    FAILED;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code payment_status_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    public static PaymentStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 결제 상태다: " + code));
    }
}
