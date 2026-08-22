package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 셀러가 손을 놓은 묶음이 닫히는가(청크 10a-2, `D2` R9).
 *
 * <p><b>여기서 보는 것은 배치가 도는지가 아니라 결과가 비는지다</b>(`36a` 가 밟은 함정).
 * 파기가 {@code closed_at} 에서 흐르므로, <b>안 닫힌 것이 하나라도 남으면</b>
 * 그 주문의 개인정보는 무기한 남는다 — 배치가 돌았다는 사실만으로는 그것을 못 막는다.
 */
@DisplayName("방치 묶음 마감")
class StaleBundleBatchTest extends PostgresTestBase {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 22, 4, 45, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private StaleBundleBatch batch;

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long sellerId;
    private long orderId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("stale-buyer@test.local", "구매자");
        sellerId = fixture.insertSeller("stale-seller", "방치셀러");
        orderId = insertOrder();
    }

    @Nested
    @DisplayName("발송 안 한 묶음은")
    class Unshipped {

        @Test
        @DisplayName("기한 + 7일이 지나면 취소된다")
        void cancelsAfterGrace() {
            long bundleId = insertBundle("preparing", NOW.minusDays(10), null);

            batch.close(NOW);

            // 제15조제2항 — 공급이 곤란하면 사유를 알리고 환급한다. 열어 두는 것은 그 요구를 미루는 것이다.
            assertThat(statusOf(bundleId)).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("기한만 지났으면 아직 안 건드린다")
        void waitsOutTheGrace() {
            long bundleId = insertBundle("preparing", NOW.minusDays(2), null);

            batch.close(NOW);

            // 기한 초과 자체는 이미 공급 곤란 통지가 나간다(`56`). 여기 이레는 그 뒤의 여유다.
            assertThat(statusOf(bundleId)).isEqualTo("preparing");
        }

        @Test
        @DisplayName("닫히면 보존 기간이 흐르기 시작한다")
        void startsRetentionClock() {
            long bundleId = insertBundle("preparing", NOW.minusDays(10), null);

            batch.close(NOW);

            // `closed_at` 이 비어 있으면 파기 대상을 고르는 질의가 그 주문을 영영 안 뽑는다.
            assertThat(closedAtOf(bundleId)).isNotNull();
        }
    }

    @Nested
    @DisplayName("보내 놓고 멈춘 묶음은")
    class Shipping {

        @Test
        @DisplayName("30일이 지나면 배송완료로 민다")
        void marksDelivered() {
            long bundleId = insertBundle("shipping", NOW.minusDays(40), NOW.minusDays(35));

            batch.close(NOW);

            assertThat(statusOf(bundleId)).isEqualTo("delivered");
        }

        @Test
        @DisplayName("확정까지 밀지 않는다")
        void stopsAtDelivered() {
            long bundleId = insertBundle("shipping", NOW.minusDays(40), NOW.minusDays(35));

            batch.close(NOW);

            // 청약철회 기간이 배송완료에서 시작한다. 확정으로 밀면 철회할 틈이 사라진다.
            assertThat(statusOf(bundleId)).isNotEqualTo("confirmed");
        }
    }

    @Nested
    @DisplayName("반품 요청은")
    class ReturnRequested {

        @Test
        @DisplayName("오래 열려 있어도 자동으로 안 닫는다")
        void neverClosesAutomatically() {
            long bundleId = insertBundle("return_requested", NOW.minusDays(60), NOW.minusDays(55));
            batch.close(NOW);

            // 제18조제2항 1호가 기산점을 「재화를 반환받은 날」로 잡는다.
            // 물건이 안 왔는데 `returned` 로 닫으면 그 기록이 거짓이 된다.
            assertThat(statusOf(bundleId)).isEqualTo("return_requested");
        }
    }

    @Nested
    @DisplayName("두 번 돌아도")
    class Idempotent {

        @Test
        @DisplayName("이미 닫힌 것은 다시 안 센다")
        void countsOnlyOnce() {
            insertBundle("preparing", NOW.minusDays(10), null);

            int first = batch.close(NOW);
            int second = batch.close(NOW);

            assertThat(first).isEqualTo(1);
            assertThat(second)
                    .describedAs("조건이 상태라 처리하고 나면 대상에서 빠진다")
                    .isZero();
        }
    }

    private String statusOf(long sellerOrderId) {
        return jdbc.sql("select status from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(String.class)
                .single();
    }

    private OffsetDateTime closedAtOf(long sellerOrderId) {
        return jdbc.sql("select closed_at from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(OffsetDateTime.class)
                .single();
    }

    private long insertBundle(String status, OffsetDateTime shipDueAt, OffsetDateTime shippedAt) {
        long id = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, status, ship_due_at, shipped_at,
                                                  return_reason)
                        values (:number, :orderId, :sellerId, 0, :status, :shipDueAt, :shippedAt,
                                :returnReason)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .param("status", status)
                .param("shipDueAt", shipDueAt)
                .param("shippedAt", shippedAt)
                // `V29` 가 반품 상태에는 사유를 요구한다. 여기는 사유가 관심사가 아니라 껍데기만 채운다.
                .param("returnReason", status.startsWith("return") ? "change_of_mind" : null)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '방치 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", id)
                .param("skuId", insertSku())
                .update();

        return id;
    }

    private long insertOrder() {
        long id = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total,
                                                payable_amount, status)
                        values (:number, :userId, 10000, 1000, 0, 10000, 'paid')
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, id);
        return id;
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '방치 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat)
                        values (:productId, 10000)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
