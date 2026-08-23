package com.projectshop.shop.inquiry;

import java.util.Locale;

import com.projectshop.shop.auth.FieldGroup;

/**
 * 문의 응답의 필드 묶음. 저장값은 {@code permission_field_group} 의
 * {@code resource = 'inquiry'} 행이다(`V62`).
 *
 * <p><b>둘로 가른 것은 보는 사람에 따라 달라지기 때문</b>이다(`4d`).
 * 감사자는 「누가 언제 무엇을 처리했나」를 보면 되고 <b>고객이 쓴 글은 그 답에 필요가 없다</b> —
 * 개인정보보호법 제3조제1항의 최소처리다.
 *
 * <p>누구에게도 안 나가는 값은 여기 오지 않고 <b>애초에 응답 record 에 칸이 없다</b>
 * (`D23` 축 2) — 공개 목록의 {@code PublicEntry} 에 낸 사람 칸이 없는 것이 그 자리다.
 */
public enum InquiryFields implements FieldGroup {

    /** 번호, 종류, 상태, 대상, 일시. 볼 수 있는 사람은 다 본다 */
    BASIC,

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
