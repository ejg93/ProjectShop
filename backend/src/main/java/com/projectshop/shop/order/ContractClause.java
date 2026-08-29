package com.projectshop.shop.order;

import java.util.Arrays;

/**
 * 주문 시점에 박제한 계약 문서가 채우는 조항. 저장값은
 * {@code order_contract_document.clause} 고 목록은 {@code order_contract_document_clause_check} 다(`V27`).
 *
 * <p><b>법이 정한 목록이라 닫혀 있다</b>(`D23` 「법이 인정한 목록은 닫는다」).
 * 전자상거래법 제13조제2항의 호를 그대로 가른다(`D2` R22).
 *
 * <p><b>문서가 아니라 호가 단위다.</b> 약관을 쪼개거나 합쳐도 이 값은 안 바뀐다 —
 * 그래서 화면이 「청약철회는 어디 적혀 있나」에 답할 수 있다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * {@link OrderQuery} 가 조항마다 정해진 순서로 내리고 화면이 호마다 다른 자리에 그린다.
 */
enum ContractClause {

    /** 5호. 청약철회의 기한·행사방법·효과 */
    WITHDRAWAL,

    /** 6호. 교환·반품·보증과 대금 환불, 환불 지연 배상금 */
    EXCHANGE,

    /** 8호. 소비자피해보상의 처리, 불만 처리, 분쟁 처리 */
    DISPUTE,

    /** 9호. 거래에 관한 약관 */
    TERMS;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 조항으로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     *
     * <p><b>입력을 거르는 자리가 아니다.</b> 조항 하나를 펼치는 경로는 모르는 값에
     * 조회가 0건이라 404 로 답한다 — 거기서 이걸 부르면 없는 조항이 500 이 된다.
     */
    static ContractClause of(String code) {
        return Arrays.stream(values())
                .filter(clause -> clause.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 계약 조항이다: " + code));
    }
}
