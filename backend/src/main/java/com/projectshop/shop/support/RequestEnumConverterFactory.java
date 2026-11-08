package com.projectshop.shop.support;

import java.util.Locale;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 요청 파라미터 글자를 열거형 상수로 바꾼다 — 값 목록이 있는 필터의 변환 한 자리다(`Q380a`, {@code D14} 「입력과 출력」).
 *
 * <p><b>상수 이름({@code name()})을 대소문자 없이 맞춘다.</b> 응답이 대문자 스네이크로 나가서(`D5`) 필터도
 * 그 표기로 들어오는데, 이 자리 전의 입구 셋이 소문자도 받았다 — 그 상위집합이라 부르던 쪽이 안 깨진다.
 *
 * <p><b>Spring 의 기본 변환에 안 맡긴다.</b> 기본값은 관례(4순위)라 판이 바뀌면 대소문자 규칙이 조용히 바뀐다
 * ({@code D23} 「안 넣은 것도 근거를 남긴다」). 여기 두면 규칙이 우리 코드에 있다.
 *
 * <p>없는 이름은 {@link IllegalArgumentException} 을 그대로 던진다 — Spring 이
 * {@code MethodArgumentTypeMismatchException} 으로 감싸고 {@code ApiExceptionHandler} 가
 * 400 {@code validation-failed} 로 내보낸다. 빈 글자는 {@code null} 이다(필터를 안 건 것과 같다).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class RequestEnumConverterFactory implements ConverterFactory<String, Enum> {

    @Override
    public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            String name = source.trim();
            if (name.isEmpty()) {
                return null;
            }
            return (T) Enum.valueOf(targetType, name.toUpperCase(Locale.ROOT));
        };
    }
}
