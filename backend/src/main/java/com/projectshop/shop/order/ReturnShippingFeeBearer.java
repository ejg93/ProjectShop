package com.projectshop.shop.order;

import java.util.Arrays;

/**
 * 반환에 드는 배송비를 누가 내나. 저장값은 {@code return_request.return_shipping_fee_bearer} 고
 * 목록은 {@code return_shipping_fee_bearer_check} 다(`V63`).
 *
 * <p><b>법이 값을 정한다</b>(전자상거래법 제18조제9항·제10항, `D2` R36). 고르는 것이 아니라
 * <b>판정 결과와 사유에서 나온다</b> — 그래서 {@link #of} 가 그 표를 들고 요청에는 칸이 없다.
 *
 * <table>
 *   <caption>제18조가 가르는 방식</caption>
 *   <tr><th>판정</th><th>사유</th><th>부담</th><th>조문</th></tr>
 *   <tr><td>{@code approved}</td><td>{@code defect}</td><td>{@link #SELLER}</td>
 *       <td>제18조제10항 — 제17조제3항의 경우</td></tr>
 *   <tr><td>{@code approved}</td><td>{@code change_of_mind}</td><td>{@link #CONSUMER}</td>
 *       <td>제18조제9항 원칙. 예외 사유가 없다</td></tr>
 *   <tr><td>{@code rejected}</td><td>—</td><td>{@link #CONSUMER}</td>
 *       <td>제18조제9항 — 제17조제3항의 경우가 <b>아니라고 판정한 것</b></td></tr>
 * </table>
 *
 * <p><b>제약이 이미 셋 있다</b>(`V63`) — 부담 주체가 판정과 같이 정해지고, 하자로 인정한 반품의
 * 배송비를 소비자에게 물리면 행이 안 들어간다. <b>그래도 타입으로 올리는 이유는</b>
 * 「막히는 것과 고를 수 없는 것은 다르다」여서다 — 제약은 잘못된 값이 <b>들어갈 때</b> 막고,
 * 타입은 <b>만들 때</b> 막는다.
 *
 * <p><b>{@code null} 이 뜻을 든다.</b> 판정 전에는 부담 주체가 없다 —
 * {@code return_request_bearer_decided_check} 가 그 짝을 강제한다.
 */
enum ReturnShippingFeeBearer {

    /** 소비자가 낸다. 단순 변심으로 인정했거나, 하자가 아니라고 판정한 경우 */
    CONSUMER,

    /** 셀러가 낸다. <b>하자로 인정한 경우뿐이다</b>(제18조제10항) */
    SELLER;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 판정 결과와 사유에서 부담 주체를 낸다. <b>고르는 자리가 아니다.</b>
     *
     * <p>요청에 칸을 만들면 셀러가 하자 반품의 배송비를 소비자에게 물릴 수 있게 된다 —
     * {@code return_request_defect_bearer_check} 가 그 행을 막지만, <b>막히는 것과
     * 고를 수 없는 것은 다르다.</b>
     */
    static ReturnShippingFeeBearer of(ReturnStatus status, OrderStatusService.ReturnReason reason) {
        return status == ReturnStatus.APPROVED && reason == OrderStatusService.ReturnReason.DEFECT
                ? SELLER
                : CONSUMER;
    }

    /**
     * 저장값을 부담 주체로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이고, 그 자리는 법이 부담을 가르는 자리다.
     */
    static ReturnShippingFeeBearer of(String code) {
        return Arrays.stream(values())
                .filter(bearer -> bearer.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 반환 배송비 부담 주체다: " + code));
    }
}
