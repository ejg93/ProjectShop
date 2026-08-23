package com.projectshop.shop.settlement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.support.BusinessCalendar;

/**
 * 한 달치 거래를 셀러별 정산서로 묶어 지급액을 확정한다(청크 19).
 *
 * <h2>무엇을 담나</h2>
 *
 * <p>정산서 한 줄이 <b>주문 항목 건별</b>이다(청크 17). 종류가 다섯이고 종류가 부호와 공급자를
 * 정한다 — 합계 네 줄로 뭉치면 「이 수수료가 어느 주문에서 나왔나」에 정산서가 답을 못 한다.
 *
 * <table>
 *   <caption>줄의 종류와 어디서 오나</caption>
 *   <tr><th>종류</th><th>대상</th><th>부호</th></tr>
 *   <tr><td>{@code sale}</td><td>그 달에 구매확정된 주문 항목</td><td>+</td></tr>
 *   <tr><td>{@code commission}</td><td>같은 항목에 박제된 수수료</td><td>−</td></tr>
 *   <tr><td>{@code shipping_fee}</td><td>구매확정된 묶음의 배송비</td><td>+</td></tr>
 *   <tr><td>{@code sale_reversal}</td><td><b>이미 정산된</b> 항목의 환불</td><td>−</td></tr>
 *   <tr><td>{@code commission_reversal}</td><td>그 환불이 돌려주는 수수료</td><td>+</td></tr>
 *   <tr><td>{@code carryover}</td><td>지난 정산서가 넘긴 음수 잔액</td><td>−</td></tr>
 * </table>
 *
 * <h2>무엇을 안 담나</h2>
 *
 * <p><b>정산 전 환불은 계산에서 빠질 뿐 줄이 안 선다</b>(`business-model.md`). 취소·반품된 묶음은
 * 구매확정이 아니라 {@code sale} 대상에서 애초에 안 잡힌다 — 되돌릴 것이 없는데 되돌림 줄을
 * 세우면 합이 두 번 움직인다.
 *
 * <p><b>지연배상금({@code refund.delay_interest})을 셀러 몫에서 안 뺀다</b>(`12a-4`).
 * 그것은 <b>우리가 늦어서 무는 돈</b>이라 셀러에게 물리면 남의 책임을 떠넘기는 것이 된다.
 * {@code refund.amount} 와 갈라 둔 이유가 이것이고, 여기서 읽는 것은 {@code refund_item} 뿐이다.
 *
 * <h2>두 번 돌아도 지급이 두 배가 되지 않는다</h2>
 *
 * <p>대상을 고르는 조건이 <b>「아직 어느 정산서에도 안 실린 것」</b>이고, 그 조건을
 * {@code settlement_item} 의 전역 부분 유니크가 한 층 아래에서 지킨다(`V52`) —
 * 앱이 조건을 빠뜨려도 같은 근거는 두 번 안 실린다.
 */
@Service
public class SettlementService {

    /** 지급일. 대상 기간이 끝난 다음 달 10일이고 쉬는 날이면 다음 영업일이다(`D3`·`D10`) */
    private static final int PAYOUT_DAY = 10;

    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;

    SettlementService(JdbcClient jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    /**
     * 한 주기를 마감한다.
     *
     * <p><b>한 트랜잭션이다.</b> 정산서와 줄이 따로 커밋되면 지연 트리거가 못 막는 구간이 생긴다 —
     * 그 트리거는 커밋 시점에 도는데 커밋이 둘이면 첫 번째가 그것대로 맞아 버린다.
     *
     * @param periodEnd 대상 기간의 마지막 날(KST). 그 달 1일부터가 대상이다
     * @return 정산서를 세운 셀러 수
     */
    @Transactional
    public int close(LocalDate periodEnd) {
        long cycleId = openCycle(periodEnd);

        List<Long> sellers = sellersToSettle(cycleId, periodEnd);
        for (long sellerId : sellers) {
            settle(cycleId, sellerId, periodEnd);
        }
        return sellers.size();
    }

    /**
     * 그 달의 주기를 연다. 이미 있으면 그것을 쓴다.
     *
     * <p>재시도가 같은 기준일로 다시 오므로 <b>여는 것 자체가 여러 번 불려도 같아야 한다.</b>
     * {@code on conflict} 이 그 자리고, 유일성은 {@code settlement_cycle_period_unique} 가 지킨다.
     */
    private long openCycle(LocalDate periodEnd) {
        LocalDate periodStart = periodEnd.withDayOfMonth(1);
        LocalDate payoutDate = calendar.nextBusinessDay(
                periodEnd.plusMonths(1).withDayOfMonth(PAYOUT_DAY));

        jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (:start, :end, :payoutDate)
                        on conflict (period_start) do nothing
                        """)
                .param("start", periodStart)
                .param("end", periodEnd)
                .param("payoutDate", payoutDate)
                .update();

        return jdbc.sql("""
                        select settlement_cycle_id from settlement_cycle
                         where period_start = :start
                        """)
                .param("start", periodStart)
                .query(Long.class)
                .single();
    }

    /**
     * 이번 주기에 정산서가 설 셀러.
     *
     * <p>넷 중 하나라도 있으면 대상이다 — 그 달의 구매확정, 배송비, 이미 정산된 건의 환불,
     * 지난 주기가 넘긴 음수 잔액이다.
     *
     * <p><b>거래가 없고 이월만 있는 셀러도 포함한다.</b> 빼면 그 채권이 아무 데도 안 남고
     * 다음 달에도 같은 이유로 빠져서 <b>영영 회수가 안 된다.</b>
     *
     * <p><b>이미 정산서가 선 셀러는 빠진다.</b> 재시도가 도중에 끊긴 회차를 다시 도는 경우고,
     * 그 셀러의 줄은 이미 다 들어가 있다 — 이어서 넣으려 하면 근거 유니크에 걸린다.
     */
    private List<Long> sellersToSettle(long cycleId, LocalDate periodEnd) {
        return jdbc.sql("""
                        select seller_id from (
                            select so.seller_id
                              from seller_order so
                             where so.status = 'confirmed'
                               and (so.closed_at at time zone 'Asia/Seoul')::date
                                   between :start and :end
                            union
                            select so.seller_id
                              from refund r
                              join seller_order so on so.seller_order_id = r.seller_order_id
                             where r.status = 'approved'
                               and (r.decided_at at time zone 'Asia/Seoul')::date
                                   between :start and :end
                            union
                            select s.seller_id
                              from settlement s
                             where s.carried_over < 0
                               and not exists (select 1 from settlement_item i
                                                where i.carried_from_settlement_id = s.settlement_id)
                        ) candidate
                         where not exists (select 1 from settlement s
                                            where s.settlement_cycle_id = :cycleId
                                              and s.seller_id = candidate.seller_id)
                         order by seller_id
                        """)
                .param("start", periodEnd.withDayOfMonth(1))
                .param("end", periodEnd)
                .param("cycleId", cycleId)
                .query(Long.class)
                .list();
    }

    /**
     * 셀러 하나의 정산서를 세운다.
     *
     * <p><b>줄을 먼저 넣고 지급액을 나중에 채운다.</b> 합계를 앱이 계산해서 같이 넣으면
     * 계산이 두 벌이 되고, 어긋나도 지연 트리거가 「저장값이 틀렸다」고만 말한다 —
     * 줄에서 되읽으면 어긋날 자리가 없어진다.
     */
    private void settle(long cycleId, long sellerId, LocalDate periodEnd) {
        long settlementId = insertStatement(cycleId, sellerId);
        LocalDate periodStart = periodEnd.withDayOfMonth(1);

        insertSaleLines(settlementId, sellerId, periodStart, periodEnd);
        insertShippingLines(settlementId, sellerId, periodStart, periodEnd);
        insertReversalLines(settlementId, sellerId, periodStart, periodEnd);
        insertCarryOverLines(settlementId, sellerId);

        settleAmount(settlementId);
    }

    /** 지급액은 줄이 다 들어간 뒤에 채운다. 그전에는 0 이고 그 값은 커밋 전에 사라진다 */
    private long insertStatement(long cycleId, long sellerId) {
        return jdbc.sql("""
                        insert into settlement (settlement_cycle_id, seller_id, payout_amount)
                        values (:cycleId, :sellerId, 0)
                        returning settlement_id
                        """)
                .param("cycleId", cycleId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();
    }

    /**
     * 그 달에 구매확정된 주문 항목의 대금과 수수료.
     *
     * <p><b>수수료를 다시 계산하지 않는다</b>(청크 18) — 주문 시점에 박제된
     * {@code order_item.commission_amount} 를 그대로 읽는다. 요율을 조인해서 정산 때 계산하면
     * 요율을 바꾼 순간 과거 주문의 정산액이 같이 바뀐다.
     *
     * <p><b>근거도 같이 옮긴다</b>(청크 18). 요율과 기준 금액이 정산 행에 있어야 셀러가
     * 「무엇에 몇 퍼센트냐」를 정산서만으로 확인한다 — 주문 표를 다시 뒤져 계산하면
     * 그 계산이 마감 때와 같다는 보장이 없다. {@code settlement_item_commission_amount_check}
     * 가 셋이 어긋나는 것을 한 행 안에서 막는다(`V55`).
     */
    private void insertSaleLines(long settlementId, long sellerId,
            LocalDate periodStart, LocalDate periodEnd) {
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, order_item_id)
                        select :settlementId, 'sale', oi.line_amount, oi.order_item_id
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.seller_id = :sellerId
                           and so.status = 'confirmed'
                           and (so.closed_at at time zone 'Asia/Seoul')::date
                               between :start and :end
                           and not exists (select 1 from settlement_item i
                                            where i.kind = 'sale'
                                              and i.order_item_id = oi.order_item_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .update();

        // 수수료가 0 인 항목은 줄이 안 선다. 0 원 줄을 제약이 막는데(`V52`) 그것이 맞다 —
        // 안 뗀 수수료를 0 으로 적으면 정산서가 「뗐는데 0 원」과 「안 뗐다」를 못 가른다.
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, order_item_id,
                                                     commission_bp, commission_base_amount)
                        select :settlementId, 'commission', -oi.commission_amount, oi.order_item_id,
                               oi.commission_bp, oi.line_amount
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.seller_id = :sellerId
                           and so.status = 'confirmed'
                           and oi.commission_amount > 0
                           and (so.closed_at at time zone 'Asia/Seoul')::date
                               between :start and :end
                           and not exists (select 1 from settlement_item i
                                            where i.kind = 'commission'
                                              and i.order_item_id = oi.order_item_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .update();
    }

    /**
     * 구매확정된 묶음의 배송비. <b>셀러가 받는다</b>(`business-model.md`).
     *
     * <p>묶음 단위라 항목별로 가를 근거가 없다. 그래서 이 줄만 {@code seller_order} 를 가리킨다.
     * <b>수수료를 안 뗀다</b> — 모의 결제라 결제 수수료 개념이 없다.
     */
    private void insertShippingLines(long settlementId, long sellerId,
            LocalDate periodStart, LocalDate periodEnd) {
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, seller_order_id)
                        select :settlementId, 'shipping_fee', so.shipping_fee, so.seller_order_id
                          from seller_order so
                         where so.seller_id = :sellerId
                           and so.status = 'confirmed'
                           and so.shipping_fee > 0
                           and (so.closed_at at time zone 'Asia/Seoul')::date
                               between :start and :end
                           and not exists (select 1 from settlement_item i
                                            where i.kind = 'shipping_fee'
                                              and i.seller_order_id = so.seller_order_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .update();
    }

    /**
     * 이미 정산된 건의 환불을 회수한다. <b>정산 후 환불이 이 자리다</b>(`business-model.md`).
     *
     * <p><b>기준은 환불을 승인한 날</b>이다. 원 주문의 확정일로 잡으면 지나간 정산서를 다시
     * 계산하게 되고 그 순간 박제가 깨진다.
     *
     * <p><b>이미 {@code sale} 로 실린 항목만 되돌린다.</b> 안 실린 것은 계산에서 빠질 뿐이라
     * 되돌릴 것이 없고, 그때 회수 줄을 세우면 안 준 돈을 두 번 빼는 것이 된다.
     *
     * <p>수수료는 <b>돌려주는 쪽</b>이라 양수다 — 거래가 없어졌으니 몰도 안 받는다.
     */
    private void insertReversalLines(long settlementId, long sellerId,
            LocalDate periodStart, LocalDate periodEnd) {
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, refund_item_id)
                        select :settlementId, 'sale_reversal', -ri.amount, ri.refund_item_id
                          from refund_item ri
                          join refund r on r.refund_id = ri.refund_id
                          join seller_order so on so.seller_order_id = r.seller_order_id
                         where so.seller_id = :sellerId
                           and r.status = 'approved'
                           and (r.decided_at at time zone 'Asia/Seoul')::date
                               between :start and :end
                           and exists (select 1 from settlement_item paid
                                        where paid.kind = 'sale'
                                          and paid.order_item_id = ri.order_item_id)
                           and not exists (select 1 from settlement_item i
                                            where i.kind = 'sale_reversal'
                                              and i.refund_item_id = ri.refund_item_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .update();

        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, refund_item_id)
                        select :settlementId, 'commission_reversal', ri.commission_refund,
                               ri.refund_item_id
                          from refund_item ri
                          join refund r on r.refund_id = ri.refund_id
                          join seller_order so on so.seller_order_id = r.seller_order_id
                         where so.seller_id = :sellerId
                           and r.status = 'approved'
                           and ri.commission_refund > 0
                           and (r.decided_at at time zone 'Asia/Seoul')::date
                               between :start and :end
                           and exists (select 1 from settlement_item paid
                                        where paid.kind = 'commission'
                                          and paid.order_item_id = ri.order_item_id)
                           and not exists (select 1 from settlement_item i
                                            where i.kind = 'commission_reversal'
                                              and i.refund_item_id = ri.refund_item_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .update();
    }

    /**
     * 지난 정산서가 넘긴 음수 잔액을 이번 정산서가 받는다.
     *
     * <p><b>아직 안 넘어간 것만</b> 고른다. 두 번 물리면 셀러가 같은 빚을 두 번 갚는데,
     * {@code settlement_item_carryover_unique} 가 그것을 한 층 아래에서 막는다(`V52`).
     */
    private void insertCarryOverLines(long settlementId, long sellerId) {
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount,
                                                     carried_from_settlement_id)
                        select :settlementId, 'carryover', s.carried_over, s.settlement_id
                          from settlement s
                         where s.seller_id = :sellerId
                           and s.carried_over < 0
                           and s.settlement_id <> :settlementId
                           and not exists (select 1 from settlement_item i
                                            where i.carried_from_settlement_id = s.settlement_id)
                        """)
                .param("settlementId", settlementId)
                .param("sellerId", sellerId)
                .update();
    }

    /**
     * 줄에서 지급액을 되읽고 이월을 정한다.
     *
     * <p>줄이 하나도 안 붙은 정산서는 지운다. 대상으로 뽑혔는데 줄이 없는 경우가 있다 —
     * 그 달의 환불이 전부 <b>정산 안 된 건</b>이었던 셀러다. <b>빈 정산서가 서면
     * 「이 달에 거래가 없었다」와 「마감이 덜 돌았다」가 안 갈리고</b>, 지연 트리거도 그것을 막는다.
     */
    private void settleAmount(long settlementId) {
        int updated = jdbc.sql("""
                        update settlement s
                           set payout_amount = totals.sum,
                               carried_over  = least(0, totals.sum),
                               updated_at    = now()
                          from (select coalesce(sum(amount), 0) as sum
                                  from settlement_item where settlement_id = :id) totals
                         where s.settlement_id = :id
                           and exists (select 1 from settlement_item
                                        where settlement_id = :id)
                        """)
                .param("id", settlementId)
                .update();

        if (updated == 0) {
            jdbc.sql("delete from settlement where settlement_id = :id")
                    .param("id", settlementId)
                    .update();
        }
    }
}
