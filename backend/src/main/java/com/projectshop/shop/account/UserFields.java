package com.projectshop.shop.account;

import com.projectshop.shop.auth.FieldGroup;

/**
 * 사용자 응답의 필드 묶음. 저장값은 {@code permission_field_group} 의 {@code resource = 'user'} 행이다.
 *
 * <p><b>{@link com.projectshop.shop.order.OrderFields} 와 다른 목록이라 타입이 둘이다</b>(`D23`).
 * {@code basic} 이 양쪽에 있지만 담는 것이 다르다 — 하나로 두면 주문 판정에 사용자 그룹을 물어도
 * 컴파일이 통과하고 조용히 거짓이 돌아온다.
 *
 * <p><b>이 파일은 자원 패키지에 있어야 하는데 지금 관객 패키지에 있다.</b>
 * 다루는 자원이 {@code app_user} 라 `5l` 이 `me` 를 가를 때 같이 옮긴다.
 */
public enum UserFields implements FieldGroup {

    /** 표시 이름, 가입일 */
    BASIC,

    /** 전자우편, 연락처 */
    CONTACT;

    @Override
    public String code() {
        return name().toLowerCase();
    }
}
