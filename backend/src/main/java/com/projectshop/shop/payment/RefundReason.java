package com.projectshop.shop.payment;

import java.util.Arrays;

/**
 * 왜 돌려주나. 저장값은 {@code refund.reason_code} 고 목록은
 * {@code refund_reason_code_check} 다(`V23`·`V25`).
 *
 * <p><b>이 값이 환급 기한의 기산점을 정한다</b>(`D2` R5, 전자상거래법 제18조제2항).
 * 같은 「취소」라도 누가 못 하게 됐느냐에 따라 조문이 갈리고, 조문이 갈리면 기산점이 갈린다.
 * 그래서 <b>사유를 안 가르고 계산만 가르면</b> 같은 사유에 다른 기한이 나오는 행이 생기고,
 * 그때 어느 쪽이 맞는지 판단할 근거가 행에 없다(`V25`).
 *
 * <p><b>{@code OrderStatusService.ReturnReason} 과 다른 축이다.</b> 저쪽은 소비자가 왜 무르나
 * (단순 변심이냐 하자냐, 제17조)고 이쪽은 <b>왜 돈이 돌아가나</b>다. 반품이 승인되면
 * 이 표에는 {@link #WITHDRAWAL} 로 들어온다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 사유마다 묶음이 어느 상태여야 하는지가 표로 박혀 있고, 기산점 계산도 사유로 갈린다.
 */
public enum RefundReason {

    /** 고객이 취소했다. 제18조제2항 3호 — 기산점이 <b>청약철회를 한 날</b>이다 */
    CANCELLED,

    /**
     * 셀러가 공급하지 못했다. 제15조제2항 — 기산점이 <b>대금을 지급한 날</b>이다.
     *
     * <p><b>{@link #CANCELLED} 에서 떼어 냈다</b>(`V25`). 셀러 취소를 청약철회로 세면
     * 기한이 결제일보다 뒤로 밀린다 — <b>법보다 늦게 잡는 것</b>이다.
     */
    SUPPLY_FAILED,

    /**
     * 운영이 취소했다. 기산점은 <b>대금을 지급한 날</b>이다.
     *
     * <p><b>판단이 안 서서 따로 뒀다</b>(`V25`). 관리자 취소는 사유가 자유 텍스트라 코드가
     * 조문을 못 고른다. 그래서 이른 쪽을 쓴다 — <b>늦게 잡으면 위반이고 일찍 잡으면
     * 우리가 손해를 볼 뿐이다.</b>
     */
    ADMIN_CANCELLED,

    /** 청약철회(반품)로 돌아왔다. 기산점이 <b>물건을 돌려받은 날</b>이다(제18조제2항 1호) */
    WITHDRAWAL,

    /** 결제가 잘못돼서 돌려준다. 기산점이 <b>대금을 지급한 날</b>이다 */
    PAYMENT_ERROR;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 사유로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code refund_reason_code_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    public static RefundReason of(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 환불 사유다: " + code));
    }
}
