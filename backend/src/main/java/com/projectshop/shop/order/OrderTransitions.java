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
     *
     * <p><b>{@code RETURN_REQUESTED} 에서 둘로 갈린다</b>(청크 43). 반품이 승인되면
     * {@code RETURNED}, <b>거절되면 {@code DELIVERED} 로 돌아간다</b> — 물건은 소비자에게
     * 있고 거래는 살아 있으므로 배송이 끝난 상태가 사실이다. 되돌아가도 청약철회 기산점은
     * 안 움직인다: 그 값은 {@code delivered} 로 갈 때 <b>박제된 것</b>이라 다시 안 센다.
     *
     * <p><b>{@code CONFIRMED} 에서 반품이 열린다</b>(청크 43). 제17조제3항의 하자 반품은
     * <b>공급받은 날부터 3개월</b>이라 구매확정으로 안 끝난다 — 확정은 우리가 정한 기한이고
     * 그 조항은 법이 준 기한이다. <b>단순 변심은 여기로 못 온다</b>: 그쪽은 제17조제1항의
     * 7일이고 확정 시점에 이미 지났다. 사유를 가르는 것은 접수 입구가 한다(`43a`).
     */
    private static final Map<Shipment, Set<Shipment>> SHIPMENT = new EnumMap<>(Map.of(
            Shipment.PREPARING, EnumSet.of(Shipment.SHIPPING, Shipment.CANCELLED),
            Shipment.SHIPPING, EnumSet.of(Shipment.DELIVERED),
            Shipment.DELIVERED, EnumSet.of(Shipment.CONFIRMED, Shipment.RETURN_REQUESTED),
            Shipment.RETURN_REQUESTED, EnumSet.of(Shipment.RETURNED, Shipment.DELIVERED),
            Shipment.CONFIRMED, EnumSet.of(Shipment.RETURN_REQUESTED),

            Shipment.CANCELLED, EnumSet.noneOf(Shipment.class),
            Shipment.RETURNED, EnumSet.noneOf(Shipment.class)));

    static boolean allows(Payment from, Payment to) {
        return PAYMENT.get(from).contains(to);
    }

    static boolean allows(Shipment from, Shipment to) {
        return SHIPMENT.get(from).contains(to);
    }

    /**
     * 저장된 상태 코드를 응답 표기로 바꾼다. <b>어느 층인지 안 물어도 된다</b>(`43a-7`).
     *
     * <p>상태 이력({@code order_status_history})은 <b>두 층이 한 목록에 섞여 들어온다</b> —
     * 한 줄만 봐서는 결제 층인지 배송 층인지 모른다. 두 층의 값이 안 겹치므로 둘 다 찾아본다.
     *
     * <p><b>열거형을 지나는 것이 요점이다.</b> 문자열을 그냥 대문자로 올리면 DB 에 모르는 값이
     * 들어와 있어도 그대로 응답에 실려 나가고, 화면이 처음 보는 값을 받는다.
     * 여기서 터지면 <b>마이그레이션과 코드가 어긋난 순간</b> 알게 된다
     * (`ProductQuery` 가 같은 판단이고 `D23` 「Java 표현」이 그 규칙이다).
     */
    static String statusName(String storedCode) {
        if (storedCode == null) {
            return null;
        }

        return Arrays.stream(Payment.values())
                .filter(status -> status.code().equals(storedCode))
                .map(Enum::name)
                .findFirst()
                .orElseGet(() -> Shipment.of(storedCode).name());
    }
}
