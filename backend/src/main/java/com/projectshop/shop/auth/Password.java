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
 *
 * <p>ASCII 로 제한하는 것은 <b>bcrypt 의 72바이트 절단 구간을 아예 안 만들려는 것</b>이다 —
 * 한글은 글자당 3바이트라 24자에서 닿지만 ASCII 는 64자가 64바이트다.
 *
 * <p>가입과 비밀번호 변경이 각자 {@code @Size} 를 들고 있었다. 규칙이 두 군데 있으면
 * <b>한쪽만 고치는 날 가입은 되는데 변경이 막힌다.</b> 그래서 하나로 모았다.
 */
@Size(min = 8, max = 64)
@Pattern(regexp = "^[\\x20-\\x7E]+$", message = "ASCII 출력 가능 문자만 쓸 수 있다")
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface Password {

    String message() default "비밀번호 규칙에 맞지 않는다";

    Class<?>[] groups() default {};

    Class<? extends jakarta.validation.Payload>[] payload() default {};
}
