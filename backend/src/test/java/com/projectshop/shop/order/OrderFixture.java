package com.projectshop.shop.order;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.simple.JdbcClient;

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

    /**
     * 직접 넣은 주문에 계약내용 서면을 붙인다(`Q3`).
     *
     * <p><b>`V31` 이 서면 없는 주문을 막는다</b> — 전자상거래법 제13조제2항 후단이 요구하는
     * 네 조항이 다 있어야 주문이 선다. 운영 경로({@code OrderService.create})는 그것을 채우는데
     * 스키마·파기·상품 테스트는 {@code shop_order} 를 손으로 넣어서 안 채운다.
     *
     * <p><b>파일마다 이 SQL 을 베끼지 않는다.</b> 조항이 늘면 베낀 수만큼 고쳐야 하고,
     * 하나를 빠뜨리면 그 테스트만 「서면이 빠졌다」로 깨져서 원인이 조항과 무관해 보인다.
     *
     * <p>어느 문서를 가리키는지는 여기서 중요하지 않다 — 시행 중인 아무 판이나 하나면 된다.
     * 판을 고르는 규칙은 {@code OrderService} 가 지고 {@code OrderContractTest} 가 본다.
     */
    public static void attachContractDocuments(JdbcClient jdbc, long orderId) {
        jdbc.sql("""
                        insert into order_contract_document (order_id, policy_document_id, clause)
                        select :orderId,
                               (select policy_document_id from policy_document
                                 where effective_at <= now()
                                 order by policy_document_id limit 1),
                               clause
                          from unnest(array['withdrawal', 'exchange', 'dispute']) as clause
                        """)
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                        insert into order_contract_document (order_id, consent_item_id, clause)
                        values (:orderId,
                                (select consent_item_id from consent_item
                                  where effective_at <= now()
                                  order by consent_item_id limit 1),
                                'terms')
                        """)
                .param("orderId", orderId)
                .update();
    }
}
