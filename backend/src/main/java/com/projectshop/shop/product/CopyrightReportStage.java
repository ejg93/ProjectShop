package com.projectshop.shop.product;

/**
 * 저작권 신고 목록을 판정 전·후로 가르는 요청 파라미터(`Q380b`, {@code D14} 「입력과 출력」).
 *
 * <p><b>저장하지 않는 요청 전용 값이다.</b> {@code copyright_report} 에 이 칸이 없고 판정 여부는
 * 판정 컬럼이 비었나로 정해진다 — 그래서 {@code code()} 를 안 둔다. 대조할 {@code check} 가 없어서
 * {@code EnumConstraintTest} 의 {@code EXEMPT} 에 이유와 함께 적혀 있다.
 *
 * <p>전에는 컨트롤러가 문자열 {@code switch} 로 갈랐다. 열거형으로 받으면 변환이
 * {@code RequestEnumConverterFactory} 한 자리를 지나고 모르는 값은 400 {@code validation-failed} 다.
 */
enum CopyrightReportStage {

    /** 판정 전. 관리자가 여는 기본 목록이다 */
    PENDING(true),

    /** 판정 후 */
    DECIDED(false);

    private final boolean pending;

    CopyrightReportStage(boolean pending) {
        this.pending = pending;
    }

    /** 판정 전 신고만 고르나 */
    boolean pending() {
        return pending;
    }
}
