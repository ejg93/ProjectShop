package com.projectshop.shop.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 확정된 정산이 지급으로 넘어가나(청크 21).
 *
 * <p><b>마감과 지급은 다른 사건이다.</b> {@code 19} 가 지급액을 확정하는 데까지 왔고 그 뒤가
 * 비어 있었다 — 마감된 정산서와 실제로 돈이 나간 정산서가 데이터로 안 갈렸다.
 *
 * <p>여기서 보는 것은 <b>한 사람이 혼자 돈을 내보낼 수 없는지</b>다. 앱 검사와 제약이
 * 두 겹으로 걸려 있어서 둘 다 확인한다 — 앱만 있으면 새 입구가 빠뜨리고,
 * 제약만 있으면 사용자가 받는 것이 500 이다(`D23` 축 2).
 */
@DisplayName("정산 지급")
class SettlementPayoutTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int COMMISSION_BP = 1000;
    private static final long COMMISSION = PRICE * COMMISSION_BP / 10_000;

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Autowired
    private SettlementCloseBatch batch;

    @Autowired
    private SettlementPayoutService payouts;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long staffId;
    private long approverId;
    private long ownerId;
    private long auditorId;
    private long sellerId;
    private String settlementNumber;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("payout-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        staffId = fixture.insertUser("payout-staff@test.local", "올리는관리자");
        fixture.grantGlobal(staffId, "admin");

        approverId = fixture.insertUser("payout-approver@test.local", "승인하는관리자");
        fixture.grantGlobal(approverId, "admin");

        auditorId = fixture.insertUser("payout-auditor@test.local", "감사자");
        fixture.grantGlobal(auditorId, "auditor");

        sellerId = fixture.insertSeller("s-payout", "지급셀러");
        fixture.verifySeller(sellerId);
        ownerId = fixture.insertUser("payout-owner@test.local", "셀러대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);

        settlementNumber = closeAndReadNumber();
    }

    @Nested
    @DisplayName("올릴 때")
    class Requesting {

        @Test
        @DisplayName("마감된 정산서를 올린다")
        void movesAClosedStatementToRequested() {
            payouts.request(staffId, settlementNumber);

            assertThat(statusOf(settlementNumber)).isEqualTo("requested");
        }

        @Test
        @DisplayName("셀러는 못 올린다")
        void refusesASeller() {
            assertThatThrownBy(() -> payouts.request(ownerId, settlementNumber))
                    .as("셀러가 올리게 하면 셀러가 안 눌러서 지급이 밀린다(12a-5 와 같은 함정)")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("감사자는 못 올린다")
        void refusesAnAuditor() {
            assertThatThrownBy(() -> payouts.request(auditorId, settlementNumber))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("두 번 올릴 수 없다")
        void cannotBeRequestedTwice() {
            payouts.request(staffId, settlementNumber);

            assertThatThrownBy(() -> payouts.request(staffId, settlementNumber))
                    .isInstanceOf(ShopException.class);
        }

        /**
         * 지급액이 0 이하인 정산서는 이월로 넘어가지 지급 대상이 아니다
         * (`business-model.md`). 앱 조건으로만 두면 새 입구가 음수를 송금한다.
         */
        @Test
        @DisplayName("줄 돈이 없으면 못 올린다")
        void refusesAStatementWithNothingToPay() {
            String negative = aNegativeSettlement();

            assertThatThrownBy(() -> payouts.request(staffId, negative))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("제약이 음수 지급을 막는다")
        void isBlockedByTheConstraintOnNegativePayout() {
            String negative = aNegativeSettlement();

            assertThatThrownBy(() -> jdbc.sql("""
                            update settlement
                               set payout_status = 'requested',
                                   payout_requested_by_user_id = :userId,
                                   payout_requested_at = now()
                             where settlement_number = :number
                            """)
                    .param("userId", staffId)
                    .param("number", negative)
                    .update())
                    .as("앱만 있으면 psql 로 넣는 경로가 그대로 통과한다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("승인할 때")
    class Approving {

        @Test
        @DisplayName("다른 관리자가 승인한다")
        void isApprovedByAnotherAdmin() {
            payouts.request(staffId, settlementNumber);
            payouts.approve(approverId, settlementNumber);

            assertThat(statusOf(settlementNumber)).isEqualTo("paid");
        }

        @Test
        @DisplayName("올린 사람은 승인 못 한다")
        void refusesTheRequester() {
            payouts.request(staffId, settlementNumber);

            assertThatThrownBy(() -> payouts.approve(staffId, settlementNumber))
                    .as("돈이 나가는 결정을 한 사람이 혼자 끝내지 않는다")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("제약이 자기승인을 막는다")
        void isBlockedByTheConstraintOnSelfApproval() {
            payouts.request(staffId, settlementNumber);

            assertThatThrownBy(() -> jdbc.sql("""
                            update settlement
                               set payout_status = 'paid',
                                   payout_decided_by_user_id = payout_requested_by_user_id,
                                   payout_decided_at = now()
                             where settlement_number = :number
                            """)
                    .param("number", settlementNumber)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("안 올라온 것은 승인 못 한다")
        void refusesAStatementThatWasNotRequested() {
            assertThatThrownBy(() -> payouts.approve(approverId, settlementNumber))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("두 번 승인해도 한 번만 나간다")
        void doesNotPayTwice() {
            payouts.request(staffId, settlementNumber);
            payouts.approve(approverId, settlementNumber);

            assertThatThrownBy(() -> payouts.approve(approverId, settlementNumber))
                    .as("조건부 UPDATE 라 둘이 동시에 와도 하나만 통과한다")
                    .isInstanceOf(ShopException.class);
        }
    }

    /**
     * <b>지급 상태가 조회 계약에 실려야 한다.</b> {@code 21} 이 상태를 만들었는데
     * {@code 20} 의 응답이 그것을 안 실으면 <b>셀러가 정산서를 봐도 「내 돈 나갔나」를 모른다.</b>
     *
     * <p>1차 마무리(2026-08-23)가 찾은 자리다 — 두 청크가 각각은 초록인 채로 어긋나 있었다.
     */
    @Nested
    @DisplayName("조회 계약은")
    class Contract {

        @Autowired
        private SettlementQuery query;

        @Test
        @DisplayName("지급 상태를 싣는다")
        void carriesThePayoutStatus() {
            assertThat(query.findOne(ownerId, settlementNumber).summary().payoutStatus())
                    .isEqualTo("PENDING");

            payouts.request(staffId, settlementNumber);
            payouts.approve(approverId, settlementNumber);

            assertThat(query.findOne(ownerId, settlementNumber).summary().payoutStatus())
                    .as("셀러가 정산서만 보고 돈이 나갔는지 알아야 한다")
                    .isEqualTo("PAID");
        }
    }

    @Nested
    @DisplayName("반려는")
    class Rejecting {

        @Test
        @DisplayName("종점이 아니다")
        void isNotTerminal() {
            payouts.request(staffId, settlementNumber);
            payouts.reject(approverId, settlementNumber);

            assertThat(statusOf(settlementNumber)).isEqualTo("rejected");
            assertThatCode(() -> payouts.request(staffId, settlementNumber))
                    .as("정산서는 (셀러, 주기) 당 하나라 환불처럼 새 행을 만들 수가 없다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다시 올리면 처리 칸이 비워진다")
        void clearsTheDecisionWhenRequestedAgain() {
            payouts.request(staffId, settlementNumber);
            payouts.reject(approverId, settlementNumber);
            payouts.request(staffId, settlementNumber);

            assertThat(decidedAtIsNull(settlementNumber))
                    .as("반려했다는 사실은 감사 로그가 든다")
                    .isTrue();
        }
    }

    private String statusOf(String number) {
        return jdbc.sql("select payout_status from settlement where settlement_number = :number")
                .param("number", number)
                .query(String.class)
                .single();
    }

    private boolean decidedAtIsNull(String number) {
        return jdbc.sql("""
                        select payout_decided_at is null from settlement
                         where settlement_number = :number
                        """)
                .param("number", number)
                .query(Boolean.class)
                .single();
    }

    /** 마감을 돌려 정산서 하나를 세우고 그 번호를 읽는다 */
    private String closeAndReadNumber() {
        confirmedOrder();
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status)
                        values ('auto_confirm', :baselineDate, now(), now(), 0, 0, 'succeeded')
                        """)
                .param("baselineDate", PERIOD_END)
                .update();
        batch.close(PERIOD_END);

        return jdbc.sql("select settlement_number from settlement where seller_id = :id")
                .param("id", sellerId)
                .query(String.class)
                .single();
    }

    /**
     * 지급액이 음수인 정산서. 마감을 거치지 않고 손으로 넣는다 —
     * 여기서 필요한 것은 그 상태의 행이지 그 상태에 이르는 경로가 아니다.
     */
    private String aNegativeSettlement() {
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values ('2026-06-01', '2026-06-30', '2026-07-10')
                        returning settlement_cycle_id
                        """)
                .query(Long.class)
                .single();

        long settlementId = jdbc.sql("""
                        insert into settlement (settlement_number, settlement_cycle_id, seller_id,
                                                payout_amount, carried_over)
                        values ('T-20260701-K3M9P7', :cycleId, :sellerId, :amount, :amount)
                        returning settlement_id
                        """)
                .param("cycleId", cycleId)
                .param("sellerId", sellerId)
                .param("amount", -COMMISSION)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, order_item_id,
                                                     commission_bp, commission_base_amount)
                        values (:id, 'commission', :amount, :orderItemId, :bp, :base)
                        """)
                .param("id", settlementId)
                .param("amount", -COMMISSION)
                .param("orderItemId", anotherOrderItem())
                .param("bp", COMMISSION_BP)
                .param("base", PRICE)
                .update();

        return "T-20260701-K3M9P7";
    }

    private long anotherOrderItem() {
        confirmedOrder();
        return jdbc.sql("""
                        select oi.order_item_id
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.seller_id = :id
                         order by oi.order_item_id desc limit 1
                        """)
                .param("id", sellerId)
                .query(Long.class)
                .single();
    }

    private void confirmedOrder() {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, :price, :commission, 0, :price)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyerId)
                .param("price", PRICE)
                .param("commission", COMMISSION)
                .query(Long.class)
                .single();

        OrderFixture.attachContractDocuments(jdbc, orderId);

        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  status, closed_at)
                        values (:number, :orderId, :sellerId, 'confirmed', :closedAt)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .param("closedAt", PERIOD_END.atTime(12, 0)
                        .atZone(BusinessCalendar.ZONE).toOffsetDateTime())
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '지급 테스트 상품',
                                :price, 1, :price, :bp, :commission)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", insertSku())
                .param("price", PRICE)
                .param("bp", COMMISSION_BP)
                .param("commission", COMMISSION)
                .update();
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '지급 테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 100 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
