package com.projectshop.shop.order;

import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 주문 응답의 개인정보 필드 묶음. 저장값은 {@code permission_field_group} 의 {@code resource = 'order'} 행이다(`Q282a`).
 *
 * <p>{@link OrderFields} 와 같은 표의 행인데 타입이 다르다 — 열면 열람 기록이 남아야 해서
 * {@code canSee} 로는 못 묻는다({@link PersonalFieldGroup}).
 */
public enum OrderPersonalFields implements PersonalFieldGroup {

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
