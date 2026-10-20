package com.projectshop.shop.auth;

/**
 * 개인정보가 담긴 필드 묶음. 저장값은 {@link FieldGroup} 과 같은 {@code permission_field_group.code} 다(`Q282a`).
 *
 * <p><b>{@link FieldGroup} 을 상속하지 않는다.</b> 상속하면 {@code Decision.canSee} 가 받아서, 열람 기록 없이
 * 개인정보 그룹을 여는 코드가 컴파일된다. 이 타입은 열람 증서({@code audit.PersonalDataAccess})로만 묻는다 —
 * 증서를 만드는 자리가 기록을 남긴다(안전성 확보조치 기준 제8조①, `D2` R44).
 *
 * <p>목록은 {@link FieldGroup} 처럼 자원 패키지에 있다.
 */
public interface PersonalFieldGroup {

    /** 저장값. DB 는 소문자다 */
    String code();
}
