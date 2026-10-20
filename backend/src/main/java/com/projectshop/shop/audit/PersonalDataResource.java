package com.projectshop.shop.audit;

/**
 * 열람 기록이 가리키는 자원(`Q286`). 저장값은 {@code audit_log.detail.resource} 다.
 *
 * <p><b>타입이 유일한 강제 지점이다.</b> 값이 {@code detail} jsonb 안이라 {@code check} 를 못 건다(`D23` 「열거값을 어디에 두나」).
 * 그전에는 글자 넷({@code order}·{@code seller_order}·{@code user}·{@code inquiry})이 네 파일에 흩어져 있어서,
 * 오타가 나면 감사 화면이 모르는 이름을 그대로 그렸다(안전성 확보조치 기준 제8조① — 접속기록이 처리한 정보를 든다).
 *
 * <p>권한 자원 이름({@code evaluator.decide} 의 첫 인자)과 글자가 같아도 다른 것이다 — 그쪽은 {@code permission} 표의 행이다.
 */
public enum PersonalDataResource {

    /** 주문 상세 — 배송지·결제 */
    ORDER("order"),

    /** 셀러 주문 상세 — 배송지 */
    SELLER_ORDER("seller_order"),

    /** 계정 — 연락처·생년월일 */
    USER("user"),

    /** 문의 목록 — 질문·답변 글 */
    INQUIRY("inquiry");

    private final String code;

    PersonalDataResource(String code) {
        this.code = code;
    }

    /** 저장값. {@code detail.resource} 에 이 글자가 들어간다 */
    public String code() {
        return code;
    }
}
