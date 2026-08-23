package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 반품 스키마가 법 요건과 상태 어긋남을 실제로 막는지 본다(청크 43+44).
 *
 * <p><b>제18조제9항·제10항이 여기서 처음 코드에 닿는다.</b> 그전에는 약관 문구(`V21`·`V28`)에만
 * 있어서 강제 지점 5순위였다 — 사람이 읽을 때만 걸렸다.
 *
 * <p><b>지연 제약 트리거는 커밋 시점에 돈다.</b> 테스트는 {@code @Transactional} 이라 커밋을
 * 안 하므로 그냥 두면 <b>한 번도 실행되지 않고 전부 초록이 된다</b> — `마무리 (3)` 이 그것을
 * 발견했다(픽스처 다섯이 막힐 줄 알았는데 927개가 다 통과했다). {@link #flush()} 가
 * {@code set constraints all immediate} 로 그 자리에서 밀린 검사를 돌린다.
 */
@DisplayName("반품 스키마")
class ReturnRequestSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long staffId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        buyerId = fixture.insertUser("return-buyer@test.local", "산사람");
        staffId = fixture.insertUser("return-staff@test.local", "검수자");

        long sellerId = fixture.insertSeller("s-return", "반품셀러");
        sellerOrderId = deliveredBundle(sellerId);
    }

    /**
     * 제18조제9항·제10항이 배송비 부담 주체를 가른다(`D2`).
     *
     * <p><b>원칙은 소비자, 예외가 판매자다.</b> 제10항의 예외는 「제17조제3항의 경우」 —
     * 재화가 표시·광고와 다르거나 계약 내용과 다르게 이행된 경우다.
     */
    @Nested
    @DisplayName("반품 배송비는")
    class ShippingFeeBearer {

        @Test
        @DisplayName("하자로 승인하면 셀러가 문다")
        void isBorneBySellerOnApprovedDefect() {
            long returnId = openReturn("defect");

            assertThatCode(() -> approve(returnId, "seller"))
                    .as("제18조제10항 — 제17조제3항의 경우에는 판매자가 부담한다")
                    .doesNotThrowAnyException();
        }

        /**
         * <b>문구가 아니라 제약이라 새 입구가 생겨도 안 빠진다.</b> 약관에만 있으면
         * 접수 화면이든 관리자 도구든 각자 그 규칙을 다시 써야 하고, 한 곳이 빠뜨리면
         * 조용히 소비자에게 물린다.
         */
        @Test
        @DisplayName("하자 승인인데 소비자에게 물리면 안 들어간다")
        void rejectsConsumerBearerOnApprovedDefect() {
            long returnId = openReturn("defect");

            assertThatThrownBy(() -> approve(returnId, "consumer"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("단순 변심으로 승인하면 소비자가 문다")
        void allowsConsumerBearerOnChangeOfMind() {
            long returnId = openReturn("change_of_mind");

            assertThatCode(() -> approve(returnId, "consumer"))
                    .as("제18조제9항 — 청약철회의 경우 반환 비용은 소비자가 부담한다")
                    .doesNotThrowAnyException();
        }

        /**
         * 거절은 <b>「제17조제3항의 경우가 아니라고 판정한 것」</b>이라 제10항의 예외가 안 걸린다.
         * 하자라고 접수했어도 검수에서 아니었으면 부담은 소비자로 간다(제17조제5항).
         */
        @Test
        @DisplayName("거절되면 소비자가 문다")
        void putsBearerOnConsumerWhenRejected() {
            long returnId = openReturn("defect");

            assertThatCode(() -> reject(returnId, "consumer")).doesNotThrowAnyException();
        }

        /**
         * <b>앞 테스트와 갈라 둔다.</b> 하나로 묶으면 실패한 문장이 트랜잭션을 죽여서
         * 뒤 문장이 「current transaction is aborted」로 같이 죽는다 — 무엇이 막혔는지가 안 갈린다.
         */
        @Test
        @DisplayName("거절인데 셀러에게 물리면 안 들어간다")
        void rejectsSellerBearerOnRejection() {
            long returnId = openReturn("defect");

            assertThatThrownBy(() -> reject(returnId, "seller"))
                    .as("거절은 「제17조제3항의 경우가 아니다」라고 판정한 것이라 제10항의 예외가 안 걸린다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("판정 전에는 부담 주체가 없다")
        void hasNoBearerBeforeDecision() {
            long returnId = openReturn("defect");

            assertThatThrownBy(() -> jdbc.sql("""
                            update return_request set return_shipping_fee_bearer = 'seller'
                             where return_request_id = :id
                            """)
                    .param("id", returnId)
                    .update())
                    .as("부담 주체는 판정과 같이 정해진다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("시각 사슬은")
    class Timeline {

        /**
         * <b>입고는 못 건너뛴다.</b> 환급 기산점이 「재화등을 반환받은 날」이라
         * (제18조제2항 1호) 그 날이 없으면 기한을 셀 수가 없다.
         */
        @Test
        @DisplayName("승인에는 입고 시각이 필요하다")
        void requiresReceivedAtOnApproval() {
            long returnId = insertReturn("defect", "requested");

            assertThatThrownBy(() -> jdbc.sql("""
                            update return_request
                               set status = 'approved', decided_at = now(),
                                   decided_by_user_id = :staff,
                                   return_shipping_fee_bearer = 'seller', restock = true
                             where return_request_id = :id
                            """)
                    .param("staff", staffId)
                    .param("id", returnId)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * <b>거절에는 입고를 안 요구한다.</b> `마무리 (3)` 이 고친 자리다 — 근거로 든
         * 제18조제2항은 <b>환급 기산점</b>을 정하는 조문이라 환급이 없는 거절에는 걸릴 자리가 없다.
         * 수거하기 전에 무를 수 있어야 한다.
         */
        @Test
        @DisplayName("거절에는 입고 시각이 필요 없다")
        void allowsRejectionWithoutReceivedAt() {
            long returnId = insertReturn("defect", "requested");

            assertThatCode(() -> jdbc.sql("""
                            update return_request
                               set status = 'rejected', decided_at = now(),
                                   decided_by_user_id = :staff,
                                   return_shipping_fee_bearer = 'consumer'
                             where return_request_id = :id
                            """)
                    .param("staff", staffId)
                    .param("id", returnId)
                    .update())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("안 지난 단계의 시각이 차 있으면 안 들어간다")
        void rejectsTimestampsFromFutureSteps() {
            long returnId = insertReturn("defect", "requested");

            assertThatThrownBy(() -> jdbc.sql("""
                            update return_request set received_at = now()
                             where return_request_id = :id
                            """)
                    .param("id", returnId)
                    .update())
                    .as("접수 상태인데 입고 시각이 있으면 그 행은 두 말을 한다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("검수자는 검수 시각과 같이 있거나 같이 없다")
        void pairsInspectorWithItsTime() {
            long returnId = insertReturn("defect", "requested");

            assertThatThrownBy(() -> jdbc.sql("""
                            update return_request set inspected_by_user_id = :staff
                             where return_request_id = :id
                            """)
                    .param("staff", staffId)
                    .param("id", returnId)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("열린 반품은")
    class OpenReturn {

        @Test
        @DisplayName("묶음당 하나뿐이다")
        void isUniquePerBundle() {
            openReturn("defect");

            assertThatThrownBy(() -> openReturn("change_of_mind"))
                    .as("수거가 한 번이라 둘이 굴러가면 어느 물건이 어느 건인지 못 가른다")
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * <b>제17조제3항의 3개월이 거절로 사라지지 않는다.</b> 하자 반품은 거절된 뒤에도
         * 다시 접수할 수 있어야 하고, 그래서 유니크가 <b>열린 것</b>에만 걸린다.
         */
        @Test
        @DisplayName("거절된 뒤에는 다시 접수할 수 있다")
        void canBeFiledAgainAfterRejection() {
            long returnId = openReturn("defect");
            reject(returnId, "consumer");

            assertThatCode(() -> openReturn("defect")).doesNotThrowAnyException();
        }
    }

    /**
     * 묶음 상태와 반품 행이 어긋나는 것을 <b>양방향</b>으로 막는다.
     *
     * <p><b>상태를 옮기지는 않는다</b>(사용자 선택) — 트리거가 옮기면
     * {@code order_status_history} 없는 전이가 생기고, 「누가 옮겼나」에 답이 없어진다.
     *
     * <p>둘 다 <b>지연</b>이라 {@link #flush()} 를 지나야 돈다.
     */
    @Nested
    @DisplayName("묶음과 반품 행은")
    class BundleAgreement {

        @Test
        @DisplayName("접수 없이 묶음만 옮기면 커밋에서 막힌다")
        void blocksBundleMovedWithoutReturn() {
            moveBundle("return_requested");

            assertThatThrownBy(ReturnRequestSchemaTest.this::flush)
                    .as("psql 이나 배치가 옮겨 놓고 수거·검수가 통째로 비는 것을 막는다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("승인 없이 묶음만 returned 면 막힌다")
        void blocksReturnedWithoutApproval() {
            openReturn("defect");
            moveBundle("return_requested");
            moveBundle("returned");

            assertThatThrownBy(ReturnRequestSchemaTest.this::flush)
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("접수하고 묶음을 옮기면 커밋된다")
        void allowsRequestedPair() {
            openReturn("defect");
            moveBundle("return_requested");

            assertThatCode(ReturnRequestSchemaTest.this::flush).doesNotThrowAnyException();
        }

        /**
         * <b>단계마다 따로 닫는다.</b> 한 트랜잭션에서 접수부터 승인까지 다 하면
         * {@code return_requested} 로 옮긴 전이가 <b>커밋 시점의 데이터</b>로 검사되는데,
         * 그때 반품은 이미 {@code approved} 라 「열린 반품이 없다」로 걸린다.
         *
         * <p>트리거가 {@code new.status}(그때의 값)와 <b>지금의 반품 행</b>을 섞어 보기 때문이고,
         * 실무 흐름은 접수와 판정이 <b>다른 트랜잭션</b>이라 그 조합이 안 생긴다.
         *
         * <p><b>둘째 단계 앞에서 지연을 다시 켠다.</b> {@code flush()} 는 그 자리에서 검사를
         * 돌리면서 <b>이후 제약을 즉시 검사로 바꿔 놓는다</b> — 그대로 두면 승인과 묶음 이동
         * 사이의 <b>한 문장 간격</b>이 어긋난 상태로 잡힌다. 실무에서는 그 둘이 한 트랜잭션이라
         * 중간이 안 보여야 맞다.
         */
        @Test
        @DisplayName("승인하고 returned 로 옮기면 커밋된다")
        void allowsApprovedPair() {
            long returnId = openReturn("defect");
            moveBundle("return_requested");
            flush();

            defer();
            approve(returnId, "seller");
            moveBundle("returned");

            assertThatCode(ReturnRequestSchemaTest.this::flush).doesNotThrowAnyException();
        }
    }

    /**
     * 밀린 지연 제약을 그 자리에서 돌린다. <b>안 부르면 롤백에 묻혀서 한 번도 안 돈다</b>.
     *
     * <p>이 문장은 검사를 돌리는 것으로 끝나지 않고 <b>이후 제약을 즉시 검사로 바꿔 둔다</b> —
     * 다시 미루려면 {@link #defer()} 를 부른다.
     */
    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    /** 다음 단계를 한 덩어리로 보게 지연을 다시 켠다 */
    private void defer() {
        jdbc.sql("set constraints all deferred").update();
    }

    /** 접수 상태의 반품 하나. 묶음은 안 건드린다 — 트리거를 밟는 테스트가 따로 옮긴다 */
    private long openReturn(String reasonCode) {
        return insertReturn(reasonCode, "requested");
    }

    private long insertReturn(String reasonCode, String status) {
        return jdbc.sql("""
                        insert into return_request (seller_order_id, status, reason_code,
                                                    requested_by_user_id)
                        values (:bundleId, :status, :reasonCode, :userId)
                        returning return_request_id
                        """)
                .param("bundleId", sellerOrderId)
                .param("status", status)
                .param("reasonCode", reasonCode)
                .param("userId", buyerId)
                .query(Long.class)
                .single();
    }

    /** 입고까지 밀고 승인한다. 승인은 입고 시각을 요구한다 */
    private void approve(long returnRequestId, String bearer) {
        jdbc.sql("""
                        update return_request
                           set status = 'approved', received_at = now(), decided_at = now(),
                               decided_by_user_id = :staff,
                               return_shipping_fee_bearer = :bearer, restock = true
                         where return_request_id = :id
                        """)
                .param("staff", staffId)
                .param("bearer", bearer)
                .param("id", returnRequestId)
                .update();
    }

    private void reject(long returnRequestId, String bearer) {
        jdbc.sql("""
                        update return_request
                           set status = 'rejected', decided_at = now(),
                               decided_by_user_id = :staff,
                               return_shipping_fee_bearer = :bearer
                         where return_request_id = :id
                        """)
                .param("staff", staffId)
                .param("bearer", bearer)
                .param("id", returnRequestId)
                .update();

        // 거절에는 사유가 필요하다 — 지연 트리거가 커밋 때 본다(`V63`).
        jdbc.sql("""
                        insert into return_note (return_request_id, decision_reason)
                        values (:id, '검수 결과 하자가 아니다')
                        on conflict (return_request_id) do update
                           set decision_reason = excluded.decision_reason
                        """)
                .param("id", returnRequestId)
                .update();
    }

    /**
     * 묶음 상태를 민다. 전이 자체는 `11-2` 가 보는 것이고 여기서 필요한 것은 트리거가 읽는 값뿐이다.
     *
     * <p><b>사유를 같이 채운다.</b> {@code seller_order_return_reason_required_check}(`V29`)가
     * {@code return_requested} 에 사유를 요구한다 — 어느 조항으로 받았는지가 기한을 정해서다.
     */
    private void moveBundle(String status) {
        jdbc.sql("""
                        update seller_order
                           set status = :status,
                               return_reason = case when cast(:status as text) = 'return_requested'
                                                    then 'defect' else return_reason end
                         where seller_order_id = :id
                        """)
                .param("status", status)
                .param("id", sellerOrderId)
                .update();
    }

    /** 배송이 끝난 묶음 하나. 반품이 붙을 수 있는 상태다 */
    private long deliveredBundle(long sellerId) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total,
                                                payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        OrderFixture.attachContractDocuments(jdbc, orderId);

        long bundleId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, status)
                        values (:number, :orderId, :sellerId, 'delivered')
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        // 항목을 넣는다. `flush()` 가 반품 트리거만 깨우는 것이 아니라
        // `V16` 의 주문 합계 트리거까지 같이 돌려서, 항목 없는 묶음은 거기서 막힌다.
        // 금액은 0 이라 합계 등식이 그대로 맞는다.
        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:bundleId, :skuId, '반품 테스트 상품', 0, 1, 0, 0, 0)
                        """)
                .param("bundleId", bundleId)
                .param("skuId", insertSku(sellerId))
                .update();

        return bundleId;
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '반품 테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 0)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 100 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
