package com.projectshop.shop.auth;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 이메일 주소 규칙. <b>이 애너테이션이 그 규칙의 유일한 출처다.</b>
 *
 * <p><b>254 는 우리가 고른 값이 아니라 표준이 정한 값이다.</b> {@code RFC 5321} 이
 * 경로(path)를 <b>꺾쇠를 포함해 256 옥텟</b>으로 제한한다(§4.5.3.1.3) — 주소 자체는 254 다.
 * 출처가 표준(2순위)이라 <b>우리가 못 고친다</b>(`D23` 「규칙의 우선순위」).
 *
 * <p><b>320 은 산수지 표준이 아니다.</b> local-part 64(§4.5.3.1.1)와 도메인 255(§4.5.3.1.2)에
 * {@code @} 하나를 더한 값인데, <b>같은 절이 그 합을 허용하지 않는다</b> — 경로 상한이 먼저 걸린다.
 *
 * <p>가입과 이메일 변경이 각자 {@code @Size} 를 들고 있었고 <b>실제로 갈려 있었다</b> —
 * 가입 254, 변경 320(`점검 L` 에서 나왔다). 가입으로 못 만드는 주소를 변경으로는 넣을 수 있었다.
 * {@link Password} 가 같은 함정을 먼저 겪고 하나로 모은 자리라 <b>그 꼴을 그대로 따른다.</b>
 *
 * <p><b>DB 제약이 같은 값을 든다</b>({@code app_user_email_length_check}, {@code V65}).
 * 앱 검증은 3위라 배치·시드·{@code psql} 로 들어오면 안 걸려서 {@code Q22} 가 2위로 내렸다.
 * <b>두 곳의 254 가 갈리는 것은 {@code LengthConstraintTest} 가 막는다.</b>
 */
@Email
@Size(max = 254)
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface EmailAddress {

    String message() default "이메일 주소 형식이 아니거나 너무 길다";

    Class<?>[] groups() default {};

    Class<? extends jakarta.validation.Payload>[] payload() default {};
}
