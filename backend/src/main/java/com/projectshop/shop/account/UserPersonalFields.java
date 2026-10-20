package com.projectshop.shop.account;

import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 사용자 응답의 개인정보 필드 묶음. 저장값은 {@code permission_field_group} 의 {@code resource = 'user'} 행이다(`Q282a`).
 *
 * <p>{@link UserFields} 와 같은 표의 행인데 타입이 다르다 — 열면 열람 기록이 남아야 해서
 * {@code canSee} 로는 못 묻는다({@link PersonalFieldGroup}).
 */
public enum UserPersonalFields implements PersonalFieldGroup {

    /** 전자우편, 연락처 */
    CONTACT,

    /** 생년월일(`11b`). 연락처를 보는 역할만 본다 — 감사자는 못 본다 */
    BIRTH;

    @Override
    public String code() {
        return name().toLowerCase();
    }
}
