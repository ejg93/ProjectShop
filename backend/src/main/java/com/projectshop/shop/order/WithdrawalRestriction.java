package com.projectshop.shop.order;

import com.projectshop.shop.support.WithdrawalRestrictionReason;

/**
 * 주문 항목에 박을 청약철회 제한 사유를 고른다(`Q125`, 전자상거래법 제17조제2항).
 *
 * <p><b>상품이 든 사유가 그대로 가지 않는다.</b> 주문 시점에 성립하는 것만 박고 나머지는 비운다 —
 * {@code order_item_withdrawal_reason_check} 가 그 목록을 닫아 두고, 왜 좁은지는 {@code V32} 가 적었다.
 *
 * <h2>값이 문자열이 아니라 열거형이다</h2>
 *
 * <p>`점검 T` 전에는 여기가 {@code String} 상수 둘을 들고 있었다 — DB 는 값을 닫았는데
 * <b>타입이 안 닫아서</b> 틀린 값이 실행해 봐야 걸렸다(`D23` 축 2: 타입 1위).
 *
 * <p>쓰는 열거형은 {@code support} 에 산다. 상품이 정하고 주문이 쓰는 값이라
 * <b>소비자가 둘</b>이고, {@code ActorType} 이 같은 이유로 그리 옮겼다(`43a-17`, 사용자 결정 2026-09-20).
 *
 * <h2>주문 쪽 목록이 더 좁고 그것을 여기가 지킨다</h2>
 *
 * <p>{@code order_item_withdrawal_reason_check} 는 값이 <b>둘</b>이고 상품 쪽은 셋이다.
 * <b>{@code EnumConstraintTest} 는 그 좁은 제약을 안 본다</b> — 회계는 열거형과 제약을 <b>정확히</b>
 * 맞추는데 값 수가 달라서 짝이 안 된다({@code ActorType} 이 제약 둘을 다는 모양을 여기엔 못 쓴다).
 * 그래서 아래 {@code switch} 와 {@code WithdrawalRestrictionTest} 가 그 좁힘을 지키는 전부다.
 */
final class WithdrawalRestriction {

    private WithdrawalRestriction() {
    }

    /**
     * 주문 시점에 성립하는 제한만 돌려준다. 성립 안 하면 {@code null} 이고, 그것이 「제한 없음」이다.
     *
     * <p>{@code DIGITAL_CONTENT} 는 그대로 선다 — 배송완료에서만 반품이 열려서 공급이 전제고,
     * 주문 시점에 성립한 것으로 본다({@code V32}).
     *
     * <p>{@code MADE_TO_ORDER} 는 <b>동의를 받았을 때만</b> 선다. 시행령 제21조가 요구한 동의라
     * 안 받았으면 제한이 없는 주문이다.
     *
     * <p>{@code COPYABLE_MEDIA} 는 여기서 절대 안 선다 — 포장 훼손은 물건이 돌아와야 아는 사실이고
     * 그 입증은 우리 몫이다(제17조제5항). 그래서 목록에도 없다.
     */
    static WithdrawalRestrictionReason agreed(
            WithdrawalRestrictionReason reason, boolean restrictionAgreed) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case DIGITAL_CONTENT -> reason;
            case MADE_TO_ORDER -> restrictionAgreed ? reason : null;
            case COPYABLE_MEDIA -> null;
        };
    }
}
