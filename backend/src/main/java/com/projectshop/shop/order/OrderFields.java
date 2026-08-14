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
    PAYMENT;

    @Override
    public String code() {
        return name().toLowerCase();
    }
}
