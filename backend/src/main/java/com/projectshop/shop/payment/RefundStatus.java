package com.projectshop.shop.payment;

import java.util.Arrays;

/**
 * 환불 요청 한 건이 어디까지 왔나. 저장값은 {@code refund.status} 고 목록은
 * {@code refund_status_check} 다(`V23`).
 *
 * <p><b>{@link PaymentStatus#APPROVED} 와 글자가 같고 뜻이 다르다.</b> 저쪽은 카드 승인이
 * 났다는 것이고 이쪽은 <b>우리가 환불해 주기로 정했다</b>는 것이다. 문자열로 두면 한쪽 값을
 * 다른 쪽 자리에 넣어도 컴파일이 통과한다({@code ProductStatus} 와 {@code SkuStatus} 가
 * {@code on_sale} 로 같은 함정을 지고 있어서 타입으로 갈라 뒀다).
 *
 * <p><b>승인·반려는 관리자와 시스템만 한다</b>(`V51`, `D2` R5). 요청은 고객도 배치도 낸다 —
 * 법이 청약철회만으로 환급 의무를 발생시켜서 사람이 요청을 안 내도 환불이 시작돼야 한다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 승인된 환불이 있는지를 정산과 스위퍼가 각각 조건으로 본다.
 */
public enum RefundStatus {

    /** 요청이 들어왔다. 고객이 내기도 하고 스위퍼가 만들기도 한다(`12a-3`) */
    REQUESTED,

    /** 환불하기로 정했다. 환급 기한이 여기서부터 센다 */
    APPROVED,

    /** 반려했다. <b>사유가 필수다</b> — 고객에게 왜 안 됐는지 답해야 한다 */
    REJECTED;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code refund_status_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    public static RefundStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 환불 상태다: " + code));
    }
}
