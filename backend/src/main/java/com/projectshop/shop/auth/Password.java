package com.projectshop.shop.auth;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 규칙(`D14`). <b>이 애너테이션이 그 규칙의 유일한 출처다.</b>
 *
 * <p>길이와 문자 집합만 본다. 조합을 강제하면 사용자가 예측 가능한 변형을 만들고 그걸 적어 둔다.
 * 강제 금지는 취향이 아니라 <b>NIST SP 800-63B 의 {@code SHALL NOT}</b> 이다.
 *
 * <p><b>최소 15자다.</b> 같은 문서 Revision 4 가 <b>비밀번호가 단독 인증수단이면 15자를
 * {@code SHALL}</b> 로 요구한다. 8자가 허용되는 것은 다인수 인증의 일부일 때뿐인데
 * <b>우리는 MFA 가 없다</b>(`D14`). 8자로 시작했다가 `D14-1` 이 개정판에 맞췄다.
 *
 * <p>ASCII 로 제한하는 것은 <b>bcrypt 의 72바이트 절단 구간을 아예 안 만들려는 것</b>이다 —
 * 한글은 글자당 3바이트라 24자에서 닿지만 ASCII 는 64자가 64바이트다.
 * 유니코드 허용은 {@code SHOULD} 라 근거를 대고 벗어난다.
 *
 * <p><b>블록리스트 대조는 아직 없다</b>(`D14-2`). 그것도 {@code SHALL} 이라
 * <b>지금 열려 있는 위반이다</b> — 목록 출처와 검사 시점을 먼저 정해야 한다.
 *
 * <p>가입과 비밀번호 변경이 각자 {@code @Size} 를 들고 있었다. 규칙이 두 군데 있으면
 * <b>한쪽만 고치는 날 가입은 되는데 변경이 막힌다.</b> 그래서 하나로 모았다 —
 * 최소 길이를 올릴 때 고칠 자리가 여기 하나뿐인 것이 그 보람이다.
 */
@Size(min = 15, max = 64)
@Pattern(regexp = "^[\\x20-\\x7E]+$", message = "ASCII 출력 가능 문자만 쓸 수 있다")
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface Password {

    String message() default "비밀번호 규칙에 맞지 않는다";

    Class<?>[] groups() default {};

    Class<? extends jakarta.validation.Payload>[] payload() default {};
}
