package com.projectshop.shop.inquiry;

import java.util.Locale;

import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 문의 응답의 개인정보 필드 묶음. 저장값은 {@code permission_field_group} 의 {@code resource = 'inquiry'} 행이다(`Q282b`).
 *
 * <p>{@link InquiryFields} 와 같은 표의 행인데 타입이 다르다 — 열면 열람 기록이 남아야 해서
 * {@code canSee} 로는 못 묻는다({@link PersonalFieldGroup}).
 */
enum InquiryPersonalFields implements PersonalFieldGroup {

    /**
     * 질문과 답변 글.
     *
     * <p><b>사람이 직접 쓰는 칸이라 무엇이 들어올지 모른다</b> — 연락처가 섞여 들어오고,
     * 그것이 5년 표에서 사유 글을 뗀 이유이자({@code 5i-2}) 환불 요청 사유를
     * 셀러에게 안 준 이유다({@code V24}).
     */
    BODY;

    @Override
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
