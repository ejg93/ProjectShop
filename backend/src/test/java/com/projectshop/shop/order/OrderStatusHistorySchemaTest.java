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
 * 상태 이력이 잘못된 행을 막는가(`V18`).
 *
 * <p>이 테이블은 <b>분쟁에서 근거로 쓰는 기록</b>이다(`D2` R6). 청약철회 기간이 배송완료
 * 시각부터라 여기가 틀리면 기간 판정이 통째로 틀린다. 그래서 전이를 넣는 코드보다
 * <b>제약이 먼저</b>다 — 입구는 앱 하나가 아니다(`D23`).
 *
 * <p>전이 규칙 자체(무엇에서 무엇으로 갈 수 있나)는 여기서 안 본다. 그건 코드에 선언하기로
 * 했고(`ADR 0009`) 청크 11-2 가 맡는다. 여기서 보는 것은 <b>어느 층의 값인지</b>와
 * <b>누가 옮겼는지</b>가 행 하나 안에서 앞뒤가 맞는지다.
 */
@DisplayName("상태 이력 스키마")
class OrderStatusHistorySchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long orderId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("history-buyer@test.local", "구매자");

        long sellerId = fixture.insertSeller("s-history", "이력셀러");
        orderId = insertOrder();
        sellerOrderId = insertSellerOrder(orderId, sellerId);
    }

    @Nested
    @DisplayName("어느 층의 이력인지")
    class Target {

        @Test
        @DisplayName("결제 이력은 주문을 가리킨다")
        void acceptsOrderRow() {
            assertThatCode(() -> insertOrderHistory("payment_pending", "paid", "system", null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("배송 이력은 셀러 주문을 가리킨다")
        void acceptsSellerOrderRow() {
            assertThatCode(() -> insertSellerOrderHistory("preparing", "shipping", "seller", userId, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("둘 다 비면 안 들어간다")
        void rejectsRowWithoutTarget() {
            assertThatThrownBy(() -> insertHistory(null, null, "preparing", "shipping", "seller", userId, null))
                    .as("무엇의 이력인지 모르는 행은 타임라인에 못 붙는다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("둘 다 차면 안 들어간다")
        void rejectsRowWithTwoTargets() {
            assertThatThrownBy(() ->
                    insertHistory(orderId, sellerOrderId, "preparing", "shipping", "seller", userId, null))
                    .as("결제 이력인지 배송 이력인지 갈리지 않는다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("상태 값은")
    class StatusValues {

        @Test
        @DisplayName("결제 이력에 배송 상태를 못 넣는다")
        void rejectsShippingStatusOnOrder() {
            assertThatThrownBy(() -> insertOrderHistory("payment_pending", "shipping", "seller", userId, null))
                    .as("한 테이블에 두 층을 받았으므로 층을 보고 값 목록을 가른다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("배송 이력에 결제 상태를 못 넣는다")
        void rejectsPaymentStatusOnSellerOrder() {
            assertThatThrownBy(() -> insertSellerOrderHistory("preparing", "paid", "seller", userId, null))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("최초 전이는 이전 상태가 없어도 된다")
        void allowsNullFromStatus() {
            assertThatCode(() -> insertOrderHistory(null, "payment_pending", "system", null, null))
                    .as("주문이 생기는 순간이 그것이다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("같은 상태로는 못 옮긴다")
        void rejectsSelfTransition() {
            assertThatThrownBy(() -> insertOrderHistory("paid", "paid", "system", null, null))
                    .as("남으면 타임라인에 같은 줄이 반복된다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("행위자는")
    class Actor {

        @Test
        @DisplayName("사람이 옮겼으면 누구인지 남는다")
        void requiresUserForHumanActor() {
            assertThatThrownBy(() -> insertSellerOrderHistory("preparing", "shipping", "seller", null, null))
                    .as("'누가 이 주문을 취소했나' 에 답하는 것이 이 테이블이다(`ADR 0007`)")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("시스템이 옮겼으면 사람이 안 붙는다")
        void rejectsUserForSystemActor() {
            assertThatThrownBy(() -> insertOrderHistory("payment_pending", "payment_expired",
                    "system", userId, null))
                    .as("배치와 결제 모듈은 지목할 사람이 없다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("모르는 종류는 안 들어간다")
        void rejectsUnknownActorType() {
            assertThatThrownBy(() -> insertSellerOrderHistory("preparing", "shipping", "robot", userId, null))
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * 실패와 성공을 한 테스트에 못 담는다. 앞 문장이 제약에 걸리면 Postgres 가
         * 그 트랜잭션을 통째로 죽여서, 뒤 문장은 무엇을 넣든 실패한다.
         */
        @Test
        @DisplayName("관리자 전이에 사유가 없으면 안 들어간다")
        void requiresReasonFromAdmin() {
            insertSellerOrderHistory("delivered", "cancelled", "admin", userId, null);

            // 사유 글이 다른 표로 가면서 한 행 안에서 안 끝나는 조건이 됐다(`5i-3`).
            // 지연 제약 트리거라 삽입이 아니라 **커밋 때** 걸린다.
            assertThatThrownBy(() -> flush())
                    .as("정상 경로가 아니라서 사유가 없으면 왜 이 모양인지 아무도 모른다(`D7`)")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("사유를 적은 관리자 전이는 들어간다")
        void acceptsAdminWithReason() {
            assertThatCode(() -> insertSellerOrderHistory("delivered", "cancelled", "admin", userId,
                    "고객 요청, CS-1234"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("남은 이력은")
    class Immutable {

        @Test
        @DisplayName("고칠 수 없다")
        void cannotBeUpdated() {
            insertOrderHistory("payment_pending", "paid", "system", null, null);

            assertThatThrownBy(() -> jdbc.sql("update order_status_history set to_status = 'payment_failed'")
                    .update())
                    .as("고친 이력은 이력이 아니다. 잘못된 전이는 새 행으로 되돌린다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("주문보다 먼저 지워야 지워진다")
        void blocksOrderDeleteUntilRemoved() {
            insertOrderHistory("payment_pending", "paid", "system", null, null);

            assertThatThrownBy(() -> jdbc.sql("delete from shop_order where order_id = :id")
                    .param("id", orderId)
                    .update())
                    .as("cascade 를 파기 수단으로 쓰지 않는다(`D23`). 파기(청크 10a)가 자식부터 지운다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("타임라인은")
    class Timeline {

        @Test
        @DisplayName("한 주문의 두 층을 시간순으로 이어 읽는다")
        void readsBothLayersInOneQuery() {
            insertOrderHistory(null, "payment_pending", "system", null, null);
            insertOrderHistory("payment_pending", "paid", "system", null, null);
            insertSellerOrderHistory("preparing", "shipping", "seller", userId, null);

            assertThat(countOf("""
                    select count(*) from order_status_history h
                     where h.order_id = %d
                        or h.seller_order_id in (select seller_order_id from seller_order
                                                  where order_id = %d)
                    """.formatted(orderId, orderId)))
                    .as("소비자에게 거래기록 열람을 제공하는 것이 이 테이블의 두 번째 용도다(`D2` R6 제3항)")
                    .isEqualTo(3);
        }
    }

    private void insertOrderHistory(String from, String to, String actorType, Long actorUserId, String reason) {
        insertHistory(orderId, null, from, to, actorType, actorUserId, reason);
    }

    private void insertSellerOrderHistory(String from, String to, String actorType, Long actorUserId,
            String reason) {
        insertHistory(null, sellerOrderId, from, to, actorType, actorUserId, reason);
    }
    /** 사유 글은 `order_status_history_note` 로 옮겼다(`5i-3`). 지연 제약 트리거가 커밋 때 그것을 본다 */
    private void insertHistory(Long order, Long sellerOrder, String from, String to,
            String actorType, Long actorUserId, String reason) {
        long historyId = jdbc.sql("""
                        insert into order_status_history (order_id, seller_order_id, from_status, to_status,
                                                          actor_type, actor_user_id)
                        values (:orderId, :sellerOrderId, :from, :to, :actorType, :actorUserId)
                        returning order_status_history_id
                        """)
                .param("orderId", order)
                .param("sellerOrderId", sellerOrder)
                .param("from", from)
                .param("to", to)
                .param("actorType", actorType)
                .param("actorUserId", actorUserId)
                .query(Long.class)
                .single();

        if (reason != null) {
            jdbc.sql("""
                            insert into order_status_history_note (order_status_history_id, reason)
                            values (:id, :reason)
                            """)
                    .param("id", historyId)
                    .param("reason", reason)
                    .update();
        }
    }

    private long insertOrder() {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values ('20260810-7QX4M9', :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, orderId);
        return orderId;
    }

    private long insertSellerOrder(long order, long sellerId) {
        return jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", order)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();
    }

    /** 밀려 있던 지연 검사를 이 자리에서 돌린다. 커밋을 안 하는 테스트가 트리거를 밟는 법이다 */
    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    private int countOf(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
