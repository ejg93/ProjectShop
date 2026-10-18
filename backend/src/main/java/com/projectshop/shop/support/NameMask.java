package com.projectshop.shop.support;

import java.text.Normalizer;

/**
 * 남에게 보이는 이름을 가린다(`Q272`).
 *
 * <p><b>후기 목록이 작성자 이름을 다른 이용자에게 보인다.</b> 처리방침 2절이 그 목적을 「이름의 일부만 보인다」로 적었다
 * (`V128`, `D2` R43 — 개인정보법 제18조, 목적 밖 이용 금지). 이름 전체를 내리면 적은 목적보다 많이 보인다.
 *
 * <p><b>모양은 관례다</b>(`D23` 축 1 의 4순위) — 첫 글자를 살리고, 세 글자 이상이면 마지막 글자도 살린다
 * (홍*동 · 김* · 남**수). 법이 정한 것은 「일부만」이고 어느 글자를 살릴지는 정하지 않았다.
 *
 * <p><b>글자는 코드 포인트로 센다.</b> {@code String.length()} 는 UTF-16 단위라 보충 평면 글자 하나를 둘로 세고,
 * 자모로 풀린 한글(NFD)은 한 음절이 셋이 된다 — 그래서 NFC 로 모은 뒤 센다.
 */
public final class NameMask {

    private static final String MASK = "*";

    private NameMask() {}

    /** 비었으면 그대로 돌려준다 — 가릴 것이 없고, 빈 값의 뜻은 부르는 쪽이 정한다 */
    public static String mask(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        int[] chars = Normalizer.normalize(name.strip(), Normalizer.Form.NFC).codePoints().toArray();
        if (chars.length <= 2) {
            return new String(chars, 0, 1) + MASK;
        }
        return new String(chars, 0, 1) + MASK.repeat(chars.length - 2) + new String(chars, chars.length - 1, 1);
    }
}
