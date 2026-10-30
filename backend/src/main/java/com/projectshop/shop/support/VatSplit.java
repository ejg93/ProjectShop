package com.projectshop.shop.support;

/**
 * 부가세 포함 금액을 공급가액과 세액으로 나눈다(`Q274`, `D2` R17).
 *
 * <p><b>수수료는 부가세 포함가다</b>(`business-model.md` 「수수료는 부가세 포함가다」). 세금계산서는 공급가액과 세액을
 * 따로 적어야 해서(부가가치세법 제32조제1항제3호) 포함가에서 거꾸로 나눈다.
 *
 * <p><b>요율은 정산 마감 때 박제한 값이다</b>(`Q320`). 세율은 공급시기의 것을 쓴다(부가가치세법 부칙 적용례 —
 * 「시행 이후 공급하는 분부터」) — 지금 상수로 다시 나누면 세율이 바뀐 날 과거 정산서의 값이 바뀐다.
 *
 * <p><b>세액은 뺄셈으로 낸다</b> — 따로 반올림해도 세율 10% 에서는 합이 맞지만 그것은 세율의 성질이다. 뺄셈이면 세율이 바뀌어도
 * 합이 원금이다. 생성자가 원금의 역산과 대조해서 <b>원금과 요율로 정해지지 않은 값은 만들 수가 없다.</b>
 *
 * <p><b>부호는 떼고 나눠서 다시 붙인다.</b> 차감 줄(음수)과 그 되돌림(양수)이 같은 금액으로 나뉘어야 한다 —
 * 정수 나눗셈은 0 쪽으로 잘라서 음수에 같은 식을 걸면 반올림이 아니게 된다.
 *
 * @param inclVat 부가세 포함 금액. 음수면 차감 줄이다
 * @param supply  공급가액
 * @param vat     세액
 * @param rateBp  부가세율(만분율). 정산 마감 때 박제한 값이다
 */
public record VatSplit(long inclVat, long supply, long vat, int rateBp) {

    /**
     * 부가가치세법 제30조의 세율(`D2` R17). <b>정산 마감 때 이 값을 박제한다</b>(`Q320`) — 바뀌면 이 상수만 바뀌고,
     * 마감이 끝난 줄은 박제한 요율로 계속 나뉜다
     */
    public static final int VAT_RATE_BP = 1_000;

    /** 만분율의 분모 */
    private static final long BP_SCALE = 10_000;

    /**
     * <b>원금과 요율로 정해진 값만 받는다</b>(마무리 63차 독립 리뷰) — 합만 보면 {@code new VatSplit(11000, 0, 11000, 1000)} 이 지나간다.
     * 공급가액이 원금의 역산과 같고 세액이 그 나머지여야 한다.
     */
    public VatSplit {
        long expected = supplyOf(inclVat, rateBp);
        if (supply != expected || vat != inclVat - expected) {
            throw new IllegalArgumentException("공급가액·세액이 원금의 역산과 다르다 — inclVat=" + inclVat
                    + " supply=" + supply + " vat=" + vat + " rateBp=" + rateBp
                    + " (공급가액은 " + expected + " 이어야 한다)");
        }
    }

    /** 지금 세율로 나눈다. 정산 마감은 SQL {@code vat_supply_amount} 로 박제하고, 이 식은 그 대조 사본이다 */
    public static VatSplit of(long inclVat) {
        return of(inclVat, VAT_RATE_BP);
    }

    public static VatSplit of(long inclVat, int rateBp) {
        long supply = supplyOf(inclVat, rateBp);
        return new VatSplit(inclVat, supply, inclVat - supply, rateBp);
    }

    /** SQL 함수 {@code vat_supply_amount}(`V132`)와 같은 식이다 — {@code SettlementSchemaTest} 가 둘을 견준다 */
    private static long supplyOf(long inclVat, int rateBp) {
        if (rateBp < 0 || rateBp > BP_SCALE) {
            throw new IllegalArgumentException("부가세율은 0~10000 만분율이다 — rateBp=" + rateBp);
        }
        long magnitude = Math.absExact(inclVat);
        long divisor = BP_SCALE + rateBp;
        // 반올림: (a × 10000 + divisor/2) / divisor — 정수로 해서 실수 오차가 안 낀다.
        // 넘치면 던진다 — SQL 의 bigint 도 넘치면 실패한다(조용히 다른 값을 내지 않는다)
        long supplyMagnitude = Math.addExact(Math.multiplyExact(magnitude, BP_SCALE), divisor / 2) / divisor;
        return Long.signum(inclVat) * supplyMagnitude;
    }
}
