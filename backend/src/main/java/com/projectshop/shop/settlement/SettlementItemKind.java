package com.projectshop.shop.settlement;

import java.util.Arrays;

/**
 * 정산 명세 한 줄이 무엇인가. 저장값은 {@code settlement_item.kind} 고 목록은
 * {@code settlement_item_kind_check} 다(`V52`).
 *
 * <p><b>이 값이 세 가지를 한꺼번에 정한다</b> — 그래서 값이 하나 늘면 코드가 반드시
 * 따라와야 하고, `D23` 「가르는 물음」의 답이 {@code check} + Java {@code enum} 이다.
 *
 * <ul>
 *   <li>부호 — 셀러에게 주는 것이 양수, 우리가 떼거나 물리는 것이 음수다
 *   <li>공급자 — 부가가치세법이 요구하는 세금계산서의 공급자다(`D2` R17)
 *   <li>근거 — {@link #SALE}·{@link #COMMISSION} 은 주문 항목에서, {@link #SHIPPING_FEE} 는
 *       묶음에서, 되돌림 둘은 환불 항목에서, {@link #CARRYOVER} 는 지난 정산에서 나온다
 * </ul>
 *
 * <p><b>그 셋의 규칙은 여기 안 싣는다</b>(`43a-13`). 부호는 {@code settlement_item_amount_sign_check},
 * 근거는 {@code settlement_item_source_check} 가 들고 있고, 공급자는 <b>생성 열</b>이라
 * 어긋날 방법 자체가 없다({@link SettlementSupplier}). Java 필드로 옮기면 <b>규칙이 두 벌이 되고
 * 강제 지점이 앱 검증(3위)으로 내려간다</b> — `D23` 축 2 는 「가능한 한 아래로」다.
 * 이 열거형이 맡는 것은 <b>값 목록과 표기</b>뿐이다.
 */
enum SettlementItemKind {

    /** 상품 대금. 셀러가 고객에게 공급한 것이다 */
    SALE,

    /** 배송비. <b>묶음 단위</b>라 항목별로 못 가른다(`business-model.md`) */
    SHIPPING_FEE,

    /** 중개수수료. 플랫폼이 셀러에게 공급한 것이라 공급자가 뒤집힌다 */
    COMMISSION,

    /** 환불로 되돌린 상품 대금 */
    SALE_REVERSAL,

    /** 환불로 되돌린 수수료. <b>환불하면 수수료도 돌려준다</b>(`D3`) */
    COMMISSION_REVERSAL,

    /** 지난 정산의 음수 잔액을 넘겨받은 것. <b>공급이 아니라 정산끼리의 조정</b>이라 공급자가 없다 */
    CARRYOVER;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code settlement_item_kind_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static SettlementItemKind of(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 정산 항목 종류다: " + code));
    }
}
