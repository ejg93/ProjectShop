package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 사건이 원천 표에서 아웃박스로 저절로 흘러드나(`Q57`, `D12`).
 *
 * <p><b>앱이 아니라 트리거가 채운다.</b> 앱이 채우면 새 경로를 만드는 사람이 그 한 줄을
 * 빠뜨리고, <b>빠뜨린 것은 아무도 안 알린 날에야 드러난다.</b> 그래서 여기서 보는 것은
 * 「서비스가 부르나」가 아니라 <b>「원천 표에 행이 생기면 아웃박스에도 생기나」</b>다.
 *
 * <p>같은 트랜잭션이라는 것이 이 설계의 값이다 — 롤백되면 사건도 같이 사라진다.
 * 그 사실을 둘째 테스트가 고정한다.
 */
@DisplayName("아웃박스 스키마")
class OutboxEventSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private static final String ORDER_NUMBER = "20260915-7QX4M8";
    private static final String REFUND_NUMBER = "R-20260915-7QX4M8";

    private long orderId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long userId = fixture.insertUser("outbox-buyer@test.local", "구매자");

        orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", ORDER_NUMBER)
                .param("userId", userId)
                .query(Long.class)
                .single();
        OrderFixture.attachContractDocuments(jdbc, orderId);
    }

    @Test
    @DisplayName("주문 전이 하나가 아웃박스 행 하나를 낳는다")
    void oneTransitionEmitsOneEvent() {
        insertOrderHistory("payment_pending", "paid");

        Map<String, Object> row = jdbc.sql("""
                        select type, subject, data->>'order_number' as order_number,
                               data->>'from_status' as from_status,
                               data->>'to_status' as to_status,
                               data->>'actor_type' as actor_type
                          from outbox_event
                         where subject = :subject
                        """)
                .param("subject", ORDER_NUMBER)
                .query()
                .singleRow();

        assertThat(row.get("type")).isEqualTo("shop.order.status_changed");
        assertThat(row.get("subject"))
                .as("봉투의 subject 는 노출 번호다 — 내부 id 를 밖에 내보이지 않는다(`D9`)")
                .isEqualTo(ORDER_NUMBER);
        assertThat(row.get("order_number")).isEqualTo(ORDER_NUMBER);
        assertThat(row.get("from_status")).isEqualTo("payment_pending");
        assertThat(row.get("to_status")).isEqualTo("paid");
        assertThat(row.get("actor_type")).isEqualTo("system");
    }

    @Test
    @DisplayName("롤백되면 아웃박스 행도 사라진다")
    void rollbackTakesTheEventWithIt() {
        // 이 테스트 클래스는 `@Transactional` 이라 메서드가 끝나면 통째로 롤백된다.
        // 여기서는 **한 문장 안에서** 그 사실을 본다 — 저장점을 잡고 되돌린다.
        jdbc.sql("savepoint before_transition").update();
        insertOrderHistory("payment_pending", "paid");
        assertThat(eventCount(ORDER_NUMBER)).isOne();

        jdbc.sql("rollback to savepoint before_transition").update();

        assertThat(eventCount(ORDER_NUMBER))
                .as("원천과 사건이 같은 트랜잭션이라는 것이 이 설계의 값이다(`D11`). "
                        + "앱이 따로 넣으면 한쪽만 남는 구간이 생긴다")
                .isZero();
    }

    @Test
    @DisplayName("앱이 직접 넣으면 거부된다")
    void rejectsDirectInsert() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into outbox_event (type, subject, occurred_at, data)
                        values ('shop.order.status_changed', 'X', now(), '{}'::jsonb)
                        """).update())
                .as("관례로 두면 안 지켜진다. 문서에만 적으면 다음 사람이 서비스에서 insert 를 쓴다")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("트리거만 채운다");
    }

    @Test
    @DisplayName("같은 상태로 다시 쓰면 사건이 아니다")
    void rewritingTheSameStatusIsNotAnEvent() {
        long refundId = insertRefund();
        int after = eventCount(REFUND_NUMBER);

        jdbc.sql("update refund set status = 'requested' where refund_id = :id")
                .param("id", refundId)
                .update();

        assertThat(eventCount(REFUND_NUMBER))
                .as("`when (old.status is distinct from new.status)` 가 없으면 아무 update 나 "
                        + "사건을 낳고 소비자가 같은 통지를 여러 번 받는다")
                .isEqualTo(after);

        // `refund_decision_check` 가 「처리된 요청에는 처리자와 시각이 있다」를 막는다.
        // 상태만 고치면 그 제약에 걸려서 **트리거를 재기도 전에 죽는다.**
        // 승인은 PG 거래번호도 요구한다(`refund_gateway_refund_number_check`) — 승인된 환불은
        // 돈이 실제로 나간 것이라 그 증거가 있어야 한다.
        jdbc.sql("""
                        update refund
                           set status = 'approved', decided_at = now(),
                               approved_by_type = 'admin', approved_by_user_id = :approver,
                               gateway_refund_number = 'GW-OUTBOX-1'
                         where refund_id = :id
                        """)
                .param("id", refundId)
                .param("approver", approverId())
                .update();

        assertThat(eventCount(REFUND_NUMBER))
                .as("값이 실제로 달라지면 사건이다")
                .isEqualTo(after + 1);
    }

    private void insertOrderHistory(String from, String to) {
        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status, actor_type)
                        values (:orderId, :from, :to, 'system')
                        """)
                .param("orderId", orderId)
                .param("from", from)
                .param("to", to)
                .update();
    }

    private long insertRefund() {
        long sellerId = new AuthFixture(jdbc).insertSeller("s-outbox", "아웃박스셀러");
        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, reason_code, amount,
                                            shipping_fee_refund, requested_by_type,
                                            requested_by_user_id, due_at)
                        values (:number, :sellerOrderId, 'cancelled', 4000, 0,
                                'system', null, now())
                        returning refund_id
                        """)
                .param("number", REFUND_NUMBER)
                .param("sellerOrderId", sellerOrderId)
                .query(Long.class)
                .single();
    }

    /** `refund_self_approval_check` 가 요청자와 승인자가 같은 것을 막아서 따로 만든다 */
    private long approverId() {
        return new AuthFixture(jdbc).insertUser("outbox-admin@test.local", "승인자");
    }

    /**
     * 이 테스트가 만든 사건만 센다.
     *
     * <p><b>표 전체를 세면 안 된다.</b> 롤백을 끄고 도는 테스트가 있어서
     * ({@code BatchLockTest} 의 {@code NOT_SUPPORTED}) 그쪽이 만든 회차 사건이
     * <b>커밋된 채로 남는다</b> — 같은 fork 에서 뒤에 도는 이 테스트가 그 행까지 세게 된다.
     * <b>따로 돌리면 초록이고 전체에서 빨간 자리였다</b>(`Q57` 실측).
     */
    private int eventCount(String subject) {
        return jdbc.sql("select count(*) from outbox_event where subject = :subject")
                .param("subject", subject)
                .query(Integer.class)
                .single();
    }
}
