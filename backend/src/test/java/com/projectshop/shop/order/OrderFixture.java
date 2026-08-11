package com.projectshop.shop.order;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트가 {@code seller_order} 를 직접 넣을 때 쓰는 노출 번호.
 *
 * <p><b>번호를 손으로 쓰지 않게 하려고 둔다.</b> 형식에 {@code check} 제약이 걸려 있고
 * 유니크라, 파일마다 상수를 박으면 테스트가 늘 때마다 충돌하거나 형식에서 걸린다.
 *
 * <p>운영 번호와 달리 난수가 아니다. 여기서 필요한 것은 예측 불가능성이 아니라
 * <b>한 테스트 실행 안에서 안 겹치는 것</b>뿐이다 — 실제 발급은 {@code OrderService} 가 한다(`D9`).
 */
public final class OrderFixture {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 6;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private OrderFixture() {
    }

    /** {@code S-20260101-22222D} 꼴. 부를 때마다 다른 값이 나온다 */
    public static String sellerOrderNumber() {
        int sequence = SEQUENCE.incrementAndGet();

        StringBuilder tail = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            tail.append(ALPHABET[(sequence >> (i * 5)) & (ALPHABET.length - 1)]);
        }
        return "S-20260101-" + tail;
    }
}
