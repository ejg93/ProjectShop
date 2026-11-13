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

    /**
     * 주문 하나와 그 아래 배송완료 묶음 하나를 SQL 로 직접 넣고 두 번호를 돌려준다(`Q431`).
     *
     * <p>금액은 전부 0 이고 계약내용 서면은 {@link #attachContractDocuments} 가 붙인다.
     * 위반 시험({@code CheckViolation*Test})이 제약을 부술 바탕 행으로 쓴다 —
     * 파일마다 같은 SQL 을 베끼면 주문 표의 칸이 늘 때 베낀 수만큼 고친다.
     */
    public static Bundle deliveredBundle(JdbcClient jdbc, long userId, long sellerId) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", sellerOrderNumber().substring(2))
                .param("userId", userId)
                .query(Long.class)
                .single();
        attachContractDocuments(jdbc, orderId);

        long bundleId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, status)
                        values (:number, :orderId, :sellerId, 'delivered')
                        returning seller_order_id
                        """)
                .param("number", sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();
        return new Bundle(orderId, bundleId);
    }

    /**
     * {@link #deliveredBundle} 위에 상품·SKU·주문 항목 한 줄을 SQL 로 넣고 다섯 번호를 돌려준다(`Q431`).
     *
     * <p>수량 1 · 줄 금액 = 단가 · 수수료 0 이다. <b>지연 제약 둘을 어긴 채로 돌려준다</b> — 재고 행이 없어
     * {@code sku_requires_stock}(`V40`)을, 단가가 0 이 아니면 주문 합계(0)와 줄 금액이 갈려 {@code order_item_amounts_check}(`V16`)를
     * 어긴다. 롤백으로 끝나는 시험은 지연 검사가 안 돌아 초록이다. {@code flush()}(즉시 검사)나 커밋을 하는 시험은 재고 행을
     * 넣고 단가 0 으로 부른다({@code ReturnRequestSchemaTest} 가 그렇다).
     */
    public static Line deliveredLine(JdbcClient jdbc, long userId, long sellerId,
                                     String productName, long unitPrice) {
        Bundle bundle = deliveredBundle(jdbc, userId, sellerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, :name)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .param("name", productName)
                .query(Long.class)
                .single();
        long skuId = jdbc.sql("insert into sku (product_id, price_incl_vat) values (:productId, :price) returning sku_id")
                .param("productId", productId)
                .param("price", unitPrice)
                .query(Long.class)
                .single();

        long itemId = jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:bundleId, :skuId, :name, :price, 1, :price, 0, 0)
                        returning order_item_id
                        """)
                .param("bundleId", bundle.bundleId())
                .param("skuId", skuId)
                .param("name", productName)
                .param("price", unitPrice)
                .query(Long.class)
                .single();
        return new Line(bundle.orderId(), bundle.bundleId(), productId, skuId, itemId);
    }

    /** {@link #deliveredBundle} 이 넣은 주문 번호와 묶음 번호. */
    public record Bundle(long orderId, long bundleId) {
    }

    /** {@link #deliveredLine} 이 넣은 주문·묶음·상품·SKU·주문 항목 번호. */
    public record Line(long orderId, long bundleId, long productId, long skuId, long itemId) {
    }
}
