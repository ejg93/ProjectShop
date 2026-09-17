package com.projectshop.shop.order;

/**
 * 청약철회 제한이 이 주문 항목에 실제로 성립하나(`D2` R4, 전자상거래법 제17조제2항).
 *
 * <p><b>계산이라 서비스 밖으로 뺐다</b>(`Q68`). 값이 입력만으로 정해지고 DB·시계를 안 본다 —
 * 그런 것이 서비스 안에 있으면 <b>그 규칙만 따로 시험할 수가 없다</b>(`D15`).
 *
 * <p><b>둘이 다르다.</b> 복제 가능한 매체는 상품 속성만으로 제한이 서고,
 * 주문 제작은 <b>소비자가 동의해야</b> 선다 — 제17조제2항 단서가 「미리 알리고 동의를 받은 경우」로
 * 그 둘을 갈랐다. 동의가 없으면 제한이 아예 없는 것이고 {@code null} 이 그 뜻이다.
 */
final class WithdrawalRestriction {

    /** 복제 가능한 매체. 포장을 뜯으면 되돌릴 수 없어 동의가 필요 없다 */
    static final String DIGITAL_CONTENT = "digital_content";
    /** 주문 제작. <b>동의가 성립 요건이다</b> */
    static final String MADE_TO_ORDER = "made_to_order";

    private WithdrawalRestriction() {
    }

    /**
     * 이 거래에서 <b>실제로 성립한</b> 청약철회 제한(`Q5`, `D2` R4).
     *
     * <p>상품에 붙은 것은 「이 사유에 해당할 수 있다」는 표시고, 제한이 서려면
     * <b>사유마다 다른 조건</b>이 차야 한다(전자상거래법 제17조제2항).
     *
     * <ul>
     *   <li>{@code digital_content}(5호) — 제공이 개시돼야 한다. 반품 접수가 배송완료에서만
     *       열려서({@code OrderStatusPolicy}) 공급이 전제고, 주문 시점에 성립한 것으로 본다</li>
     *   <li>{@code made_to_order}(시행령 제21조) — <b>거래마다 별도 고지와 소비자의 동의</b>가
     *       요건이다. 안 받았으면 제한이 없는 주문이고, 나중에 무를 수 있다</li>
     *   <li>{@code copyable_media}(4호) — <b>여기서는 절대 성립하지 않는다.</b> 포장 훼손은
     *       물건이 돌아와야 아는 사실이고 제17조제5항이 그 입증을 우리에게 지웠다.
     *       판단은 반품 검수 축(43·44)이 한다</li>
     * </ul>
     *
     * @param reason           상품에 박힌 제한 사유. 없으면 {@code null}
     * @param restrictionAgreed 소비자가 그 제한에 동의했나
     */
    static String agreed(String reason, boolean restrictionAgreed) {
        if (reason == null) {
            return null;
        }
        if (DIGITAL_CONTENT.equals(reason)) {
            return reason;
        }
        return MADE_TO_ORDER.equals(reason) && restrictionAgreed ? reason : null;
    }
}
