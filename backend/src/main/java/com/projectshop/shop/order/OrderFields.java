package com.projectshop.shop.order;

import com.projectshop.shop.auth.FieldGroup;

/**
 * 주문 응답의 필드 묶음. 저장값은 {@code permission_field_group} 의 {@code resource = 'order'} 행이다.
 *
 * <p>목록이 자원 패키지에 있는 이유는 {@link FieldGroup} 에 적었다.
 *
 * <p>이 셋이 갈려 있는 것은 <b>보는 사람에 따라 달라지기 때문</b>이다(`4d`).
 * 누구에게도 안 나가는 값은 여기 오지 않고 애초에 응답 record 에 칸이 없다(`D23` 축 2).
 */
public enum OrderFields implements FieldGroup {

    /** 주문번호, 금액, 상태, 일시. 볼 수 있는 사람은 다 본다 */
    BASIC,

    /**
     * 수령인, 연락처, 배송지 주소.
     *
     * <p>셀러에게 나가는 것이 <b>제3자 제공</b>이라 배송에 필요한 만큼만 묶었다(`D2` R8).
     */
    SHIPPING,

    /**
     * 결제 수단, 승인번호.
     *
     * <p><b>셀러의 {@code order:read} 에 안 붙어 있다</b>(`V6`). 셀러가 볼 이유가 없고,
     * 그것이 `D2` R18 의 이미 구현된 절반이다.
     */
    PAYMENT,

    /**
     * 환불 금액, 상태, 환급 기한.
     *
     * <p><b>{@link #PAYMENT} 에 안 얹고 따로 있다</b>(`V24`, 사용자 선택). 보는 사람이 달라서다 —
     * 환불은 셀러 정산에서 차감되는 돈이라(`D3`) 셀러가 못 보면 명세가 왜 그 금액인지
     * 확인할 자리가 없고, 결제 수단·승인번호는 셀러가 볼 이유가 없다.
     *
     * <p><b>지금은 {@code order:read} 를 가진 역할이 전부 본다.</b> 그래도 {@link #BASIC} 처럼
     * 안 묶는 이유는 <b>갈릴 것이 예정돼 있어서</b>다 — 정산(`19`~`21`)이 서면 셀러가 보는 범위와
     * 관리자가 보는 범위가 달라진다. 그때 그룹을 새로 만들면 이미 나간 응답의 모양이 바뀐다.
     *
     * <p><b>요청 사유는 이 그룹에 없다.</b> 소비자가 쓴 자유 텍스트라 무엇이 들어올지 모르고,
     * 셀러에게 나가는 것이 제3자 제공이다(`D2` R8).
     */
    REFUND;

    @Override
    public String code() {
        return name().toLowerCase();
    }
}
