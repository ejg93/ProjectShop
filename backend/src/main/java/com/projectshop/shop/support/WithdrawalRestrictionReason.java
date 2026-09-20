package com.projectshop.shop.support;

import java.util.Arrays;

/**
 * 청약철회를 제한할 수 있는 사유. 저장값은 {@code product.withdrawal_restriction_reason} 이고
 * 목록은 {@code product_withdrawal_reason_check} 다.
 *
 * <p><b>법이 인정한 것만 들어간다</b>(전자상거래법 제17조제2항, `D2` R4). 자유 텍스트로 두면
 * 법이 인정하지 않는 사유가 화면에 나가고, 그건 제한 자체가 무효가 되는 길이다.
 *
 * <p>R4 가 열거한 다섯 중 셋만 여기 있다. 나머지 둘은 <b>받은 물건의 상태 판단</b>이라
 * 상품 속성이 아니다 - 조문을 통째로 옮기지 않고 데이터가 되는 것만 골랐다(`D23`).
 *
 * <p><b>{@code support} 에 산다</b>(`Q125`). 상품이 값을 정하고 주문이 그것을 쓰므로 소비자가 둘이고,
 * {@code ActorType} 이 같은 이유로 여기 왔다(`43a-17`). 도메인 패키지에 둔 채로 열면
 * 그 패키지가 조용히 공용이 된다.
 *
 * <p><b>아직 문자열인 자리가 남았다.</b> 주문 쓰기 경로는 `Q125` 가 이 타입으로 돌렸지만
 * 상품 등록·수정({@code ProductService})은 문자열을 그대로 받고 {@code check} 제약이 막는다 —
 * 강제 지점이 2위에 있어 급하지 않고, 상품 등록 화면(`13f`)이 이 목록을 골라 보내게 되면 그때 옮긴다.
 */
public enum WithdrawalRestrictionReason {

    /** 복제가 가능한 음반·영상·소프트웨어의 포장을 훼손한 경우 */
    COPYABLE_MEDIA,

    /** 용역 또는 디지털콘텐츠의 제공이 개시된 경우 */
    DIGITAL_CONTENT,

    /** 주문에 따라 개별적으로 생산되는 재화 */
    MADE_TO_ORDER;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 사유로 되돌린다. 제한이 없으면 {@code null} 이 그대로 나간다.
     *
     * <p><b>모르는 값이면 터진다.</b> 조용히 통과시키면 화면이 처음 보는 값을 받아
     * 아무 안내도 못 그리는데, 그 자리는 법이 고지를 요구하는 자리다.
     */
    public static WithdrawalRestrictionReason of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 청약철회 제한 사유다: " + code));
    }
}
