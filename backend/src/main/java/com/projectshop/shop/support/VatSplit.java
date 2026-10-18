package com.projectshop.shop.support;

/**
 * 부가세 포함 금액을 공급가액과 세액으로 나눈다(`Q274`, `D2` R17).
 *
 * <p><b>수수료는 부가세 포함가다</b>(`business-model.md` 「수수료는 부가세 포함가다」). 세금계산서는 공급가액과 세액을
 * 따로 적어야 해서(부가가치세법 제32조제1항제3호) 포함가에서 거꾸로 나눈다.
 *
 * <p><b>세액은 뺄셈으로 낸다</b> — 따로 반올림해도 세율 10% 에서는 합이 맞지만 그것은 세율의 성질이다. 뺄셈이면 세율이 바뀌어도
 * 합이 원금이다. 생성자가 그 합을 원금과 대조해서 <b>합이 안 맞는 값은 만들 수가 없다.</b>
 *
 * <p><b>부호는 떼고 나눠서 다시 붙인다.</b> 차감 줄(음수)과 그 되돌림(양수)이 같은 금액으로 나뉘어야 한다 —
 * 정수 나눗셈은 0 쪽으로 잘라서 음수에 같은 식을 걸면 반올림이 아니게 된다.
 *
 * @param inclVat 부가세 포함 금액. 음수면 차감 줄이다
 * @param supply  공급가액
 * @param vat     세액
 */
public record VatSplit(long inclVat, long supply, long vat) {

    /** 부가가치세법 제30조의 세율(`D2` R17). 바뀌면 이 상수와 아래 나눗셈이 같이 바뀐다 */
    public static final int VAT_RATE_PERCENT = 10;

    public VatSplit {
        if (supply + vat != inclVat) {
            throw new IllegalArgumentException(
                    "공급가액과 세액의 합이 원금과 다르다 — inclVat=" + inclVat + " supply=" + supply + " vat=" + vat);
        }
    }

    public static VatSplit of(long inclVat) {
        long magnitude = Math.abs(inclVat);
        long divisor = 100 + VAT_RATE_PERCENT;
        // 반올림: (a × 100 + divisor/2) / divisor — 정수로 해서 실수 오차가 안 낀다
        long supplyMagnitude = (magnitude * 100 + divisor / 2) / divisor;
        long sign = Long.signum(inclVat);
        long supply = sign * supplyMagnitude;
        return new VatSplit(inclVat, supply, inclVat - supply);
    }
}
