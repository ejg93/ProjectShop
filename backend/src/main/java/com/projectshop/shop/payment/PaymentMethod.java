package com.projectshop.shop.payment;

import java.util.Arrays;

/**
 * 무엇으로 냈나. 저장값은 {@code payment.method} 고 목록은 {@code payment_method_check} 다(`V22`).
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 수단마다 <b>받는 것도 판정도 갈린다</b>: {@link #CARD} 는 카드번호가 필수고
 * ({@code PaymentService.requireCardNumberForCard}), 게이트웨이가 그 번호로 승인·거절을 가른다.
 * {@link #TRANSFER} 는 카드정보가 없어서 거절을 유도할 자리 자체가 없다.
 *
 * <p><b>카드번호는 어디에도 안 남는다.</b> 여신전문금융업법 제19조라 결제 표에 그 컬럼이 없다
 * (`V22`). 남는 것은 발급사와 뒤 네 자리뿐이다.
 */
public enum PaymentMethod {

    /** 카드. 번호가 필수고 승인·거절이 갈린다 */
    CARD,

    /** 계좌이체. 카드정보가 없어 게이트웨이가 늘 승인한다 */
    TRANSFER;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 수단으로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code payment_method_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     *
     * <p><b>입력을 거르는 자리가 아니다.</b> 결제 요청이 모르는 수단을 보내는 것은
     * 사용자 잘못이라 422 로 답해야 하는데, 여기서 부르면 500 이 된다.
     */
    public static PaymentMethod of(String code) {
        return Arrays.stream(values())
                .filter(method -> method.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 결제 수단이다: " + code));
    }
}
