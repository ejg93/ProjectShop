package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.List;

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
    private static final String SELLER_ORDER_NUMBER = "S-20260915-7QX4M8";
    private static final String SETTLEMENT_NUMBER = "T-20260915-7QX4M8";
    private static final String BATCH_NAME = "outbox_probe";

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
                        select e.type, e.subject, e.source, e.occurred_at,
                               e.data->>'order_number' as order_number,
                               e.data->>'from_status' as from_status,
                               e.data->>'to_status' as to_status,
                               e.data->>'actor_type' as actor_type,
                               h.occurred_at as history_occurred_at
                          from outbox_event e
                          join order_status_history h on h.order_id = :orderId
                         where e.subject = :subject
                        """)
                .param("orderId", orderId)
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

        // **봉투 속성도 잰다**(마무리 18차 독립 리뷰). 앞서 `data` 만 보고 `occurred_at`·`source` 를
        // 안 봤는데, `D12` 봉투 표가 그 둘을 못박아 뒀다 — 값이 틀려도 빨간 것이 없었다.
        assertThat(row.get("source"))
                .as("`ErrorCode` 의 `tag:` 체계와 같다(`D12` 봉투). 바뀌면 계약 변경이다")
                .isEqualTo("tag:projectshop.example,2026:shop");
        assertThat(row.get("occurred_at"))
                .as("**원천 행의 시각이지 발행 시각이 아니다**(`D12`). "
                        + "여기가 `now()` 로 굳으면 소비자가 순서를 잘못 판단한다")
                .isEqualTo(row.get("history_occurred_at"));
    }

    @Test
    @DisplayName("원천 표 여섯이 저마다 사건을 낳는다")
    void everySourceEmitsItsOwnEvent() {
        emitEveryEvent();
    }

    /** 원천 여섯을 한 번씩 건드려 사건 일곱을 만든다. 아래 페이로드 대조들이 같은 입력을 쓴다(`Q66`). */
    private void emitEveryEvent() {
        // **앞서 주문 이력 하나만 쟀다**(마무리 18차 독립 리뷰). 나머지 다섯은 아무 테스트도 안 지나서
        // **정산 트리거를 통째로 지워도 초록이었다.** 게이트가 막는다고 적어 둔 범위와
        // 실제로 재는 범위가 갈려 있던 자리다.
        long sellerOrderId = insertSellerOrder();

        insertSellerOrderHistory(sellerOrderId);
        assertEvent("shop.seller_order.status_changed", sellerOrderNumberOf(sellerOrderId));

        // 처음 넣은 재고도 이동이다(`V41`). 그 백필이 이미 사건을 낳으므로 따로 안 옮긴다.
        long skuId = insertSku(sellerOrderId);
        assertEvent("shop.sku.stock_moved", String.valueOf(skuId));

        insertBatchRun();
        assertEvent("shop.batch_run.finished", BATCH_NAME);

        long returnId = insertReturnRequest(sellerOrderId);
        // 상태마다 짝이 되는 시각 칸이 있어야 한다(`return_request_timestamps_check`).
        jdbc.sql("""
                        update return_request
                           set status = 'picked_up', picked_up_at = now()
                         where return_request_id = :id
                        """)
                .param("id", returnId)
                .update();
        // **subject 가 내부 id 였다**(`Q66`). `D9` 가 반품에 「없다 — seller_order_number」로 정해 뒀는데
        // `V70` 이 `D12` 표의 낡은 문장을 옮겼다. 재고와 반품이 둘 다 작은 내부 id 를 실어서
        // **한 대상에 사건이 둘로 세어진** 것이 마무리 18차가 밟은 실물이다.
        assertEvent("shop.return_request.status_changed", sellerOrderNumberOf(sellerOrderId));

        // `paid` 는 요청 사슬과 결정 사슬을 둘 다 채워야 한다(`V57`), 그리고 요청자와
        // 승인자가 달라야 한다(`settlement_payout_self_approval_check`).
        AuthFixture people = new AuthFixture(jdbc);
        long requester = people.insertUser("outbox-payout-req@test.local", "요청자");
        long approver = people.insertUser("outbox-payout-app@test.local", "승인자");

        insertSettlement();
        jdbc.sql("""
                        update settlement
                           set payout_status = 'paid',
                               payout_requested_at = now(), payout_requested_by_user_id = :req,
                               payout_decided_at = now(), payout_decided_by_user_id = :app
                         where settlement_number = :number
                        """)
                .param("req", requester)
                .param("app", approver)
                .param("number", SETTLEMENT_NUMBER)
                .update();
        assertEvent("shop.settlement.payout_changed", SETTLEMENT_NUMBER);
    }

    @Test
    @DisplayName("넣은 사건은 못 고치고 발행 표시도 못 되돌린다")
    void eventsAreImmutable() {
        insertOrderHistory("payment_pending", "paid");

        // **저장점이 필요하다.** 첫 예외가 트랜잭션을 죽여서 다음 문장이 `25P02` 로 떨어진다 —
        // `Q49` 가 고친 바로 그 함정이고, 여기서도 그대로 밟았다(마무리 18차).
        jdbc.sql("savepoint before_update").update();
        assertThatThrownBy(() -> jdbc.sql(
                        "update outbox_event set subject = 'X' where subject = :subject")
                .param("subject", ORDER_NUMBER)
                .update())
                .as("넣기만 하는 표에 불변 트리거를 붙이는 것이 이 저장소의 관례다(`V18`·`V27`)")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("못 고친다");
        jdbc.sql("rollback to savepoint before_update").update();

        jdbc.sql("update outbox_event set published_at = now() where subject = :subject")
                .param("subject", ORDER_NUMBER)
                .update();

        assertThatThrownBy(() -> jdbc.sql(
                        "update outbox_event set published_at = null where subject = :subject")
                .param("subject", ORDER_NUMBER)
                .update())
                .as("되돌리면 같은 사건이 두 번 나간다")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("다시 나간다");
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

    /**
     * 그 종류·대상의 사건이 <b>정확히 하나</b> 났나.
     *
     * <p><b>대상만으로는 못 가린다</b>(마무리 18차 실측). 노출 번호가 없는 자원은 내부 id 를
     * 그대로 {@code subject} 에 싣는데, 재고와 반품이 둘 다 그렇다 —
     * 작은 수라 <b>서로 같은 값이 되어 한 대상에 사건이 둘로 세어졌다.</b>
     * {@code type} 이 있어야 갈린다. 노출 번호를 쓰라는 `D9` 의 이유가 이 자리에서 보인다.
     */
    private void assertEvent(String type, String subject) {
        assertThat(jdbc.sql("""
                        select count(*) from outbox_event
                         where type = :type and subject = :subject
                        """)
                .param("type", type)
                .param("subject", subject)
                .query(Integer.class)
                .single())
                .as("%s 사건이 %s 앞으로 하나 나야 한다", type, subject)
                .isOne();
    }

    private long insertSellerOrder() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("s-outbox", "아웃박스셀러");
        // 상품을 팔려면 신원이 확인돼야 한다(청크 3c 의 check_product_sale_allowed).
        fixture.verifySeller(sellerId);
        return jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", SELLER_ORDER_NUMBER)
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();
    }

    private String sellerOrderNumberOf(long sellerOrderId) {
        return jdbc.sql("select seller_order_number from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(String.class)
                .single();
    }

    /** `order_status_history_actor_user_check` — 사람이 옮긴 전이는 누구인지 남아야 한다 */
    private void insertSellerOrderHistory(long sellerOrderId) {
        long actor = new AuthFixture(jdbc).insertUser("outbox-actor@test.local", "행위자");
        jdbc.sql("""
                        insert into order_status_history (seller_order_id, from_status, to_status,
                                                          actor_type, actor_user_id)
                        values (:id, 'preparing', 'shipping', 'seller', :actor)
                        """)
                .param("id", sellerOrderId)
                .param("actor", actor)
                .update();
    }

    private long insertSku(long sellerOrderId) {
        long sellerId = jdbc.sql("select seller_id from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();
        long ownerId = new AuthFixture(jdbc).insertUser("outbox-seller@test.local", "셀러주인");
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '아웃박스 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        // `sku_stock` 에 처음 넣는 것도 이동이다(`V41` 의 `record_initial_stock`) —
        // **이 삽입 하나가 이동 하나를 만들고 그것이 사건 하나를 낳는다.** 따로 안 옮긴다.
        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 5 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }

    private void insertBatchRun() {
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status)
                        values (:name, current_date, now(), now(), 0, 0, 'succeeded')
                        """)
                .param("name", BATCH_NAME)
                .update();
    }

    private long insertReturnRequest(long sellerOrderId) {
        long requester = new AuthFixture(jdbc).insertUser("outbox-return@test.local", "반품자");
        return jdbc.sql("""
                        insert into return_request (seller_order_id, reason_code, requested_by_user_id)
                        values (:id, 'change_of_mind', :user)
                        returning return_request_id
                        """)
                .param("id", sellerOrderId)
                .param("user", requester)
                .query(Long.class)
                .single();
    }

    private void insertSettlement() {
        long sellerId = new AuthFixture(jdbc).insertSeller("s-outbox2", "정산셀러");
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (current_date - 30, current_date - 1, current_date)
                        returning settlement_cycle_id
                        """)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into settlement (settlement_cycle_id, seller_id, settlement_number,
                                                payout_amount)
                        values (:cycle, :seller, :number, 5000)
                        """)
                .param("cycle", cycleId)
                .param("seller", sellerId)
                .param("number", SETTLEMENT_NUMBER)
                .update();
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
        long sellerOrderId = insertSellerOrder();

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

    /**
     * 승인자를 따로 만드는 이유는 {@code refund_approved_by_user_check} 다 —
     * {@code approved_by_type} 이 {@code admin} 이면 {@code approved_by_user_id} 가 있어야 한다(`V51`).
     *
     * <p><b>{@code refund_self_approval_check} 가 아니다</b>(마무리 18차 독립 리뷰).
     * 이 픽스처는 {@code requested_by_user_id} 를 {@code null} 로 넣어서 그 제약에 애초에 안 걸린다 —
     * <b>안 걸리는 제약을 걸린다고 적어 두면</b> 다음 사람이 그 제약을 고칠 때 이 테스트를 근거로 삼는다.
     */
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

    /**
     * 사건마다 {@code data} 가 `D12` 페이로드 규칙을 지키나(`Q66`).
     *
     * <p><b>앞의 대조는 `subject` 만 봤다.</b> 그래서 봉투 안에 내부 id 가 섞여 있어도 초록이었다 —
     * 마무리 18차 독립 리뷰가 규칙 넷이 깨진 것을 찾았고, 그중 하나는 실물로 밟혔다
     * (재고와 반품이 둘 다 작은 내부 id 를 실어 <b>한 대상에 사건이 둘로 세어졌다</b>).
     *
     * <p>재는 것은 규칙 1·2다 — <b>개인정보를 안 싣는다</b>와 <b>노출 번호를 쓴다</b>.
     * {@code sku_id} 만 예외인데 그 자원에는 노출 번호가 없다(`D9`).
     */
    @Test
    @DisplayName("사건의 data 에 내부 id 와 개인정보가 없다")
    void payloadsCarryExposedNumbersOnly() {
        emitEveryEvent();

        List<String> offenders = jdbc.sql("""
                        select type || ' → ' || key
                          from outbox_event, jsonb_object_keys(data) as key
                         where key like '%\\_id'
                            or key in ('email', 'phone', 'name', 'display_name',
                                       'receiver_name', 'address1', 'address2')
                         order by 1
                        """)
                .query(String.class)
                .list();

        assertThat(offenders)
                .as("`D12` 페이로드 규칙 1·2 — 식별자만 싣고 노출 번호를 쓴다. "
                        + "노출 번호가 없는 자원만 내부 id 를 쓴다(sku_id)")
                .isEqualTo(List.of("shop.sku.stock_moved → sku_id"));
    }

    /**
     * 전이 사건은 <b>누가 옮겼나</b>를 같이 싣는다(`D12` 페이로드 규칙 3).
     *
     * <p><b>반품만 못 싣는다.</b> 원천 표에 주체의 종류를 담는 칸이 없고
     * ({@code requested_by_user_id}·{@code decided_by_user_id} 뿐이다), 사람 id 는 규칙 1 이 막는다.
     * <b>안 넣은 것도 근거를 남긴다</b>(`D23`) — 종류 칸이 서는 청크가 이 목록을 줄인다.
     */
    @Test
    @DisplayName("전이 사건은 주체의 종류를 싣는다")
    void transitionsCarryTheActorType() {
        emitEveryEvent();

        // **`?` 를 안 쓴다.** jsonb 의 존재 연산자인데 JDBC 가 자리표시자로 읽어서 터진다 —
        // `jsonb_exists` 가 같은 것을 함수로 부른다.
        List<String> without = jdbc.sql("""
                        select distinct type from outbox_event
                         where (type like '%status_changed' or type like '%payout_changed')
                           and not jsonb_exists(data, 'actor_type')
                         order by 1
                        """)
                .query(String.class)
                .list();

        assertThat(without)
                .as("전이면 actor_type 을 싣는다. 못 싣는 것은 원천 표에 종류 칸이 없는 것뿐이다")
                .isEqualTo(List.of("shop.return_request.status_changed"));
    }

}
