package com.projectshop.shop.support;

import java.util.function.Function;

/**
 * 저장값을 응답 표기로 바꾼다. <b>열거값은 대문자 스네이크다</b>(`D5` 「값의 형식」).
 *
 * <p><b>열거형을 지나가는 것이 요점이다.</b> 문자열을 그냥 대문자로 올리면 DB 에 모르는 값이
 * 들어와 있어도 그대로 응답에 실려 나가고, 화면이 처음 보는 값을 받는데 서버는 아무 말도
 * 안 한다. {@code of} 를 지나면 <b>마이그레이션과 코드가 어긋난 순간</b> 거기서 터진다.
 *
 * <p><b>여기 모은 것은 null 가드다</b>(`43a-10`). 그전에는 같은 한 줄이 <b>여섯 파일에 열여덟 벌</b>
 * 흩어져 있었다 — {@code storedCode == null ? null : X.of(storedCode).name()}.
 * 한 벌이 가드를 빠뜨리면 {@code NullPointerException} 이고, <b>한 벌이 열거형을 안 지나면
 * 저장값이 그대로 샌다.</b> 뒤엣것이 실제로 일어났다(`43a-6`).
 *
 * <p><b>어느 열거형인지는 부르는 쪽이 적는다</b> — {@code EnumValue.of(rs.getString("status"),
 * Shipment::of)}. 그래서 이 자리는 열거형을 하나도 모르고, {@code support} 에 있어도
 * 자원 패키지로 향하는 의존이 안 생긴다.
 */
public final class EnumValue {

    private EnumValue() {
    }

    /**
     * @param storedCode DB 에 담긴 소문자 코드. <b>비어 있으면 비어 있는 채로 나간다</b> —
     *                   값이 없는 것과 모르는 값은 다르다(`D23` 「빈 값에 뜻을 싣지 않는다」)
     * @param lookup     그 자원의 열거형 조회. 모르는 값에 터지는 것이 이 인자의 몫이다
     * @return 대문자 스네이크. 열거형 상수 이름 그대로다
     */
    public static <E extends Enum<E>> String of(String storedCode, Function<String, E> lookup) {
        return storedCode == null ? null : lookup.apply(storedCode).name();
    }
}
