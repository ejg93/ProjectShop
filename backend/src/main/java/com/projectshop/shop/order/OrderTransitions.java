package com.projectshop.shop.order;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 무엇에서 무엇으로 갈 수 있나. 두 층의 전이표를 여기 하나에 선언한다(`ADR 0009`).
 *
 * <p><b>표를 데이터로 안 둔 이유가 있다.</b> 전이는 도메인 규칙이지 운영이 바꾸는 설정이 아니다.
 * DB 에 두면 바꿀 때 배포가 없어서 좋아 보이지만, 그 표가 바뀌는 순간
 * <b>코드의 전제(예: 배송완료를 지나면 기한이 박제돼 있다)가 조용히 깨진다.</b>
 *
 * <p><b>누가 옮기느냐는 여기서 안 본다.</b> 그건 권한 축이고 청크 11a 가 붙인다.
 * 여기 있는 것은 "이 순서로 갈 수 있나" 뿐이다. 둘을 섞으면 같은 조건이 두 군데서 판정된다.
 */
final class OrderTransitions {

    private OrderTransitions() {
    }

    /** 결제. `shop_order` 에 붙는다(`D7`) */
    enum Payment {
        PAYMENT_PENDING, PAID, PAYMENT_EXPIRED, PAYMENT_FAILED;

        String code() {
            return name().toLowerCase();
        }

        static Payment of(String code) {
            return Arrays.stream(values())
                    .filter(status -> status.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("모르는 결제 상태다: " + code));
        }
    }

    /** 배송. `seller_order` 에 붙는다(`D7`). 결제 전 구간도 `PREPARING` 이다 */
    enum Shipment {
        PREPARING, SHIPPING, DELIVERED, CONFIRMED, CANCELLED, RETURN_REQUESTED, RETURNED;

        String code() {
            return name().toLowerCase();
        }

        static Shipment of(String code) {
            return Arrays.stream(values())
                    .filter(status -> status.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("모르는 배송 상태다: " + code));
        }
    }

    /**
     * 결제 전이표.
     *
     * <p>{@code PAID} 에서 나가는 길이 없다. 환불은 결제를 되돌리는 것이 아니라
     * 새 사건으로 쌓는 것이라(청크 12a) 이 표의 밖이다.
     */
    private static final Map<Payment, Set<Payment>> PAYMENT = new EnumMap<>(Map.of(
            Payment.PAYMENT_PENDING,
            EnumSet.of(Payment.PAID, Payment.PAYMENT_EXPIRED, Payment.PAYMENT_FAILED),

            Payment.PAID, EnumSet.noneOf(Payment.class),
            Payment.PAYMENT_EXPIRED, EnumSet.noneOf(Payment.class),
            Payment.PAYMENT_FAILED, EnumSet.noneOf(Payment.class)));

    /**
     * 배송 전이표.
     *
     * <p><b>{@code DELIVERED} 를 지나면 앞으로 못 돌아간다.</b> 되돌릴 수 있으면
     * 청약철회 기산점을 옮길 수 있고, 그건 소비자의 기간을 줄이는 일이다(`D7`).
     *
     * <p>배송 중 취소는 없다. 물건이 이미 떠났으면 취소가 아니라 반품이다(`glossary.md`).
     */
    private static final Map<Shipment, Set<Shipment>> SHIPMENT = new EnumMap<>(Map.of(
            Shipment.PREPARING, EnumSet.of(Shipment.SHIPPING, Shipment.CANCELLED),
            Shipment.SHIPPING, EnumSet.of(Shipment.DELIVERED),
            Shipment.DELIVERED, EnumSet.of(Shipment.CONFIRMED, Shipment.RETURN_REQUESTED),
            Shipment.RETURN_REQUESTED, EnumSet.of(Shipment.RETURNED),

            Shipment.CONFIRMED, EnumSet.noneOf(Shipment.class),
            Shipment.CANCELLED, EnumSet.noneOf(Shipment.class),
            Shipment.RETURNED, EnumSet.noneOf(Shipment.class)));

    static boolean allows(Payment from, Payment to) {
        return PAYMENT.get(from).contains(to);
    }

    static boolean allows(Shipment from, Shipment to) {
        return SHIPMENT.get(from).contains(to);
    }
}
