package com.projectshop.shop.settlement;

import java.util.Arrays;

/**
 * 정산 명세 한 줄을 누가 공급했나. <b>부가가치세법이 요구하는 공급자다</b>(`D2` R17).
 * 저장값은 {@code settlement_item.supplier} 다(`V52`).
 *
 * <p>상품 대금과 배송비는 <b>셀러가 고객에게</b> 공급한 것이고, 중개수수료는
 * <b>플랫폼이 셀러에게</b> 공급한 것이다. 한 줄로 뭉치면 세금계산서를 못 가른다.
 *
 * <p><b>이 값은 받는 것이 아니라 만들어진다.</b> {@code supplier} 는 {@code kind} 에서
 * 나오는 <b>생성 열</b>이라 종류와 어긋난 행이 <b>성립할 수 없다</b> — {@code check} 로 짝을
 * 검사하는 것보다 한 층 위다(`D23` 축 2 의 1위, 구조. `money-invariants.md`).
 *
 * <p><b>그래서 이 열거형은 매핑을 안 든다</b>(`43a-13`). 「어느 종류가 어느 공급자냐」를
 * 여기 적으면 <b>DB 의 생성 식과 두 벌이 되고</b>, 한쪽만 고친 날 컴파일도 테스트도 초록이다.
 * 이 열거형이 맡는 것은 <b>값 목록과 표기</b>뿐이다.
 *
 * <p><b>{@link SettlementItemKind#CARRYOVER} 는 값이 없다.</b> 이월은 공급이 아니라
 * 정산끼리의 조정이라 공급자가 비어 있고, 응답에도 {@code null} 로 나간다.
 */
enum SettlementSupplier {

    /** 셀러가 고객에게 공급했다. 상품 대금·배송비와 그 되돌림 */
    SELLER,

    /** 플랫폼이 셀러에게 공급했다. 중개수수료와 그 되돌림 */
    PLATFORM;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 공급자로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> 생성 열이라 여기 오는 모르는 값은
     * <b>마이그레이션의 생성 식과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static SettlementSupplier of(String code) {
        return Arrays.stream(values())
                .filter(supplier -> supplier.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 정산 공급자다: " + code));
    }
}
