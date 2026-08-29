package com.projectshop.shop.inquiry;

import java.util.Arrays;

/**
 * 무엇에 대한 문의인가. 저장값은 {@code inquiry.kind} 고 목록은
 * {@code inquiry_kind_check} 다(`V53`·`V61`).
 *
 * <p><b>이 값이 대상과 기한과 보존 기간을 한꺼번에 정한다.</b> 그래서 값이 하나 늘면
 * 코드가 반드시 따라와야 한다(`D23` 「가르는 물음」).
 *
 * <ul>
 *   <li>대상 — {@link #PRODUCT} 에만 상품이 붙고({@code inquiry_product_check})
 *       {@link #ORDER} 에만 묶음이 붙는다
 *   <li>기한 — {@link #PROCESSING_STOP} 만 <b>10일</b>이다(개인정보 보호법 시행령 제44조제2항, `D2` R28)
 *   <li>보존 — {@link #DISPUTE} 는 전자상거래법 시행령 제6조 4호의 <b>분쟁처리 기록</b>이라 3년이다
 * </ul>
 *
 * <p><b>{@link #ORDER} 를 {@link #DISPUTE} 로 대신하면 안 된다</b>(`V61`). 단순 주문 문의까지
 * 분쟁 기록에 쌓이면 <b>「분쟁이 몇 건이었나」에 답을 못 한다.</b>
 *
 * <p><b>둘은 법이 만든 창구다.</b> {@link #PROCESSING_STOP} 은 개인정보 보호법 제37조,
 * {@link #ACCESS_OBJECTION} 은 열람 요구에 대한 이의다 — 이름을 바꾸거나 합치면
 * 그 창구가 있다는 사실이 코드에서 사라진다.
 */
enum InquiryKind {

    /** 상품 문의. 상품이 붙는다 */
    PRODUCT,

    /** 주문 문의. 셀러 묶음이 붙고, 셀러가 자기 것만 보는 근거도 그 값이다 */
    ORDER,

    /** 개인정보 처리정지 요구(법 제37조). <b>10일 안에 답해야 한다</b> */
    PROCESSING_STOP,

    /** 열람 요구에 대한 이의 */
    ACCESS_OBJECTION,

    /** 불만·분쟁. <b>3년 보존</b>이라 여기에 단순 문의를 섞으면 건수가 안 맞는다 */
    DISPUTE;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code inquiry_kind_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static InquiryKind of(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 문의 종류다: " + code));
    }
}
