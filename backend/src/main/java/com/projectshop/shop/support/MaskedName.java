package com.projectshop.shop.support;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 남에게 보이도록 가린 이름(`Q281`). 만드는 길은 {@link #of} 하나고, 그 길이 {@link NameMask} 를 거친다.
 *
 * <p><b>가리지 않은 이름은 이 타입이 될 수 없다.</b> 응답 칸이 {@code String} 이면 매퍼가 가림을 빼도 컴파일이 통과하고
 * 막는 것이 시험뿐이었다. 칸을 이 타입으로 두면 그 실수가 컴파일 오류다(`D23` 축 2, 처리방침 2절 —
 * 개인정보법 제18조, `D2` R43).
 *
 * <p><b>record 가 아니다</b> — record 는 canonical 생성자를 숨길 수 없어서 가리지 않은 값으로도 만들어진다.
 *
 * <p>응답에는 문자열로 나간다({@link JsonValue}). 화면 계약({@code writerName: string})은 그대로다.
 */
public final class MaskedName {

    private final String value;

    private MaskedName(String value) {
        this.value = value;
    }

    /** 표시 이름을 가린다. 비었으면 그대로다({@link NameMask#mask}) */
    public static MaskedName of(String displayName) {
        return new MaskedName(NameMask.mask(displayName));
    }

    /** 가린 이름. 응답에 이 값이 그대로 나간다 */
    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaskedName name && Objects.equals(value, name.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
