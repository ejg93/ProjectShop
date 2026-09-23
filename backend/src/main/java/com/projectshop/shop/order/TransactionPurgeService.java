package com.projectshop.shop.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.TaxRetention;

/**
 * 거래 축의 보존 기간이 지난 것을 파기한다.
 *
 * <p>계정 축은 {@code AccountPurgeService}(청크 5i)가 한다. 갈라 둔 이유는 <b>주기가 다르기</b> 때문이다 —
 * 개인정보 파기는 매일이고 거래기록 파기는 매월이다(`D13`).
 *
 * <p><b>법이 상한만 정하고 하한을 안 정했다.</b> 전자상거래법 제6조는 거래기록 보존을 의무로 두면서(제1항),
 * 그와 관련된 개인정보(성명·주소·전자우편주소)는 동의 철회에도 <b>"보존할 수 있다"</b> 고 적었다(제2항).
 * 보존이 권리지 의무가 아니므로 <b>쓸 일이 끝나면 버리는 쪽</b>을 골랐다 —
 * 그래야 배송지를 주문에서 분리해 둔 이유가 살아난다.
 *
 * <p>부르는 것은 {@code TransactionPurgeBatch} 다(청크 36) — 매월 1일 04:00 KST.
 */
@Service
public class TransactionPurgeService {

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 배송지를 지우기까지. <b>법이 정한 값이 아니라 우리가 정한 값이다.</b>
     *
     * <p>배송지의 쓸모는 배송이고 거래가 끝나면 목적이 달성된다. 6개월은 반품·교환·분쟁이
     * 늦게 오는 것까지 덮는 여유고, 분쟁 기록 자체는 3년 남지만 <b>거기에 주소는 필요 없다.</b>
     */
    private static final int SHIPPING_MONTHS = 6;

    /** 계약·청약철회와 대금결제·재화공급 기록(`D2` R6, 전자상거래법 시행령 제6조) */
    private static final int ORDER_YEARS = 5;

    /** 분쟁이 늦게 터져도 닿는 기간(`D13`). 동의 이력과 같은 기간이라 대응 범위가 안 어긋난다 */
    private static final int AUDIT_YEARS = 3;

    /**
     * 문의를 두는 기간. <b>법이 정한 값이다</b> — 전자상거래법 시행령 제6조의
     * 「소비자의 불만 또는 분쟁처리에 관한 기록」 3년(`D2` R6).
     *
     * <p>{@link #AUDIT_YEARS} 와 값이 같은데 근거가 다르다. 감사 로그의 3년은 우리 판단이고
     * 이쪽은 법이라, 한쪽이 바뀌어도 다른 쪽을 따라 고치면 안 된다.
     */
    private static final int INQUIRY_YEARS = 3;

    /**
     * 손해배상 <b>사유 글</b>을 두는 기간(`43a-4b`). {@link #INQUIRY_YEARS} 와 근거가 같다 —
     * 같은 시행령 제6조 4호의 「분쟁처리에 관한 기록」이고, 배상이 곧 그 처리의 결과다.
     *
     * <p><b>판정 행은 안 지운다.</b> 그쪽은 정산의 근거라 장부와 같이 살고
     * (`data-lifecycle.md` 「정산」), 사라지는 것은 사람이 쓴 글뿐이다 —
     * {@code refund_note}·{@code order_status_history_note} 와 같은 모양이다.
     *
     * <p><b>그래도 상수를 갈라 둔다.</b> 값이 같다고 한 이름으로 묶으면 한쪽 근거가 바뀔 때
     * 다른 쪽까지 따라 움직인다 — {@link #AUDIT_YEARS} 를 가른 것과 같은 이유다.
     */
    private static final int COMPENSATION_NOTE_YEARS = 3;

    /**
     * 배치 회차 이력을 두는 기간(`D19`). <b>개인정보가 아니라 법이 걸리는 파기가 아니다.</b>
     *
     * <p>여기 얹은 이유는 <b>수명이 끝난 행을 치운다는 일이 같아서</b>다 —
     * 멱등키를 개인정보 파기에 얹은 것과 같은 자리다. 1년이면 작년 같은 달과 대조할 수 있다.
     */
    private static final int BATCH_RUN_YEARS = 1;

    /**
     * 개인화된 발송 본문을 두는 기간(`D18-1`).
     *
     * <p><b>법이 정한 값이 아니라 우리가 정한 값이다.</b> 제6조제2항이 거래기록에 딸린 개인정보를
     * 「보존할 수 있다」고 재량으로 둔 자리라, 배송지·카드와 같은 여섯 달을 골랐다.
     * 본문은 사본이고 문안은 판에, 금액은 주문·결제에 남아서 복원된다.
     */
    private static final int NOTIFICATION_BODY_MONTHS = 6;

    /**
     * 광고성 정보 발송 이력을 두는 기간(`D18-1`).
     *
     * <p>시행령 제6조제1항 1호 「표시·광고에 관한 기록」이 여섯 달이다.
     * 거래 통지는 같은 항 2·3호라 { #ORDER_YEARS} 를 쓴다 — 같은 표인데 칸이 다르다.
     */
    private static final int ADVERTISEMENT_MONTHS = 6;

    /**
     * 발행이 끝난 아웃박스 사건을 두는 기간(`D13`).
     *
     * <p><b>보낸 편지의 사본이라 원본이 따로 있다</b>(`D12`) — 원천 표(`order_status_history` 등)가
     * 그 사실을 든다. 그래서 짧다. <b>안 보낸 행은 안 지운다</b> — 그것은 사본이 아니라
     * 아직 아무도 못 받은 편지다.
     *
     * <p>이 값이 곧 <b>재생(replay)할 수 있는 창</b>이다. 소비자가 지난 사건을 다시 받아야 할 일이
     * 생기면 늘리는 자리가 여기 하나다(`event-catalog.md` 「지금 안 하는 것」).
     */
    private static final int OUTBOX_DAYS = 7;

    private final JdbcClient jdbc;
    private final ImagePipeline images;

    TransactionPurgeService(JdbcClient jdbc, ImagePipeline images) {
        this.jdbc = jdbc;
        this.images = images;
    }

    /**
     * @param shippingAddresses 지운 배송지 행 수
     * @param paymentCards      지운 카드 정보 행 수. 결제 행은 남는다
     * @param orders            보존 기간이 지나 지운 주문 수
     * @param auditLogs         보존 기간이 지나 지운 감사 로그 수
     * @param batchRuns         보존 기간이 지나 지운 배치 회차 이력 수
     * @param notificationBodies 보존 기간이 지나 지운 발송 본문 수. 메타는 남는다
     * @param notifications     보존 기간이 지나 지운 발송 이력 수
     * @param refundNotes       보존 기간이 지나 지운 환불 사유 글 수. 환불 자체는 남는다
     * @param historyNotes      보존 기간이 지나 지운 전이 사유 글 수. 이력 자체는 남는다
     * @param inquiries         보존 기간이 지나 지운 문의 수. 질문·답변 글이 같이 사라진다
     * @param returnPickups     보존 기간이 지나 지운 반품 수거지 수. 반품 행은 남는다
     * @param returnNotes       보존 기간이 지나 지운 반품 사유 글 수. 반품 행은 남는다
     * @param compensationNotes 보존 기간이 지나 지운 배상 사유 글 수. 판정은 남는다
     * @param settlementCycles  세법 보존이 끝나 지운 정산 주기 수. 주기 통째다(`D2` R41)
     * @param compensations     세법 보존이 끝나 지운 손해배상 판정 수(`D2` R41)
     */
    public record Purged(int shippingAddresses, int paymentCards, int orders,
            int auditLogs, int batchRuns, int notificationBodies, int notifications,
            int refundNotes, int historyNotes, int inquiries,
            int returnPickups, int returnNotes, int compensationNotes,
            int settlementCycles, int compensations, int outboxEvents) {}

    /**
     * 오늘 기준으로 파기한다. 배치가 이 자리를 부른다.
     *
     * <p>기준 시각을 <b>전날 24시로 고정</b>한다(`D10`). 몇 시에 몇 번 돌든 대상이 같아야 재실행이 안전하다.
     */
    @Transactional
    public Purged purge() {
        return purge(LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime());
    }

    @Transactional
    public Purged purge(OffsetDateTime baseline) {
        int shippingAddresses = deleteExpiredShipping(baseline.minusMonths(SHIPPING_MONTHS));
        int paymentCards = deleteExpiredPaymentCards(baseline.minusMonths(SHIPPING_MONTHS));
        int compensationNotes =
                deleteExpiredCompensationNotes(baseline.minusYears(COMPENSATION_NOTE_YEARS));

        // **주문보다 먼저다**(`43a-28b`). 셋이 주문을 restrict 로 잡고 있어서, 남아 있으면
        // 아래 주문 삭제가 거부되고 **이 회차 전체가 롤백된다** — 그날 사라졌어야 할
        // 다른 사람의 개인정보까지 안 사라진다. 순서를 지키는 것을 PurgeBlockerTest 가 잰다.
        int settlementCycles = deleteExpiredSettlements(baseline);
        int compensations = deleteExpiredCompensations(baseline);
        int inquiries = deleteExpiredInquiries(baseline.minusYears(INQUIRY_YEARS));

        int orders = deleteExpiredOrders(baseline.minusYears(ORDER_YEARS));
        int auditLogs = deleteExpiredAuditLogs(baseline.minusYears(AUDIT_YEARS));
        int batchRuns = deleteExpiredBatchRuns(baseline.minusYears(BATCH_RUN_YEARS));
        int historyNotes = deleteExpiredHistoryNotes(baseline.minusMonths(SHIPPING_MONTHS));
        int refundNotes = deleteExpiredRefundNotes(baseline.minusMonths(SHIPPING_MONTHS));
        int notificationBodies =
                deleteExpiredNotificationBodies(baseline.minusMonths(NOTIFICATION_BODY_MONTHS));
        int notifications = deleteExpiredNotifications(
                baseline.minusMonths(ADVERTISEMENT_MONTHS), baseline.minusYears(ORDER_YEARS));

        int returnPickups = deleteExpiredReturnPickups(baseline.minusMonths(SHIPPING_MONTHS));
        int returnNotes = deleteExpiredReturnNotes(baseline.minusMonths(SHIPPING_MONTHS));

        // 보낸 편지의 사본이라 순서에 안 얽힌다 — 아무도 이 표를 외래키로 안 잡는다.
        int outboxEvents = deleteExpiredOutboxEvents(baseline.minusDays(OUTBOX_DAYS));

        return new Purged(shippingAddresses, paymentCards, orders, auditLogs, batchRuns,
                notificationBodies, notifications, refundNotes, historyNotes, inquiries,
                returnPickups, returnNotes, compensationNotes, settlementCycles, compensations,
                outboxEvents);
    }

    /**
     * 수거지를 지운다. <b>반품 행은 그대로 남는다</b> — {@code order_shipping} 과 같은 모양이다.
     *
     * <p>보내는 사람 이름·연락처·주소라 <b>배송지와 같은 성질</b>이고 수명도 같다(거래 종료 + 6개월).
     * 반품 사실(언제 접수했고 누가 배송비를 무나)은 5년을 채우고 사람 정보만 먼저 사라진다.
     *
     * <p><b>넣는 코드보다 먼저 든다</b>(청크 43). 접수 입구는 {@code 43a} 라 지금 이 표가 비어
     * 있는데, 그 순서를 뒤집으면 <b>수집과 파기 사이가 위반 구간</b>이 된다(`D23`) —
     * {@code user_consent.acted_ip} 가 그렇게 됐다.
     */
    private int deleteExpiredReturnPickups(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("""
                        delete from return_pickup
                         where return_request_id in (
                             select rr.return_request_id from return_request rr
                               join seller_order so on so.seller_order_id = rr.seller_order_id
                              where so.order_id in (:ids)
                         )
                        """)
                .param("ids", orderIds)
                .update();
    }

    /**
     * 반품 사유 글을 지운다. <b>반품 행은 그대로 남는다</b>({@code refund_note} 와 같은 자리).
     *
     * <p>사람이 쓴 글이라 <b>글 안에 연락처가 섞여 들어온다</b> — 「집 앞에 두고 가셨는데 010-…」
     * 같은 것이고, 그것이 5년 표에서 사유 글을 뗀 이유다(`5i-2`).
     *
     * <p><b>{@code reason_code} 는 안 지운다.</b> 그쪽은 {@code check} 로 닫힌 열거값이라
     * 사람 글이 안 들어오고, 「무슨 사유로 몇 건」은 그 값이 답한다.
     */
    private int deleteExpiredReturnNotes(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("""
                        delete from return_note
                         where return_request_id in (
                             select rr.return_request_id from return_request rr
                               join seller_order so on so.seller_order_id = rr.seller_order_id
                              where so.order_id in (:ids)
                         )
                        """)
                .param("ids", orderIds)
                .update();
    }

    /**
     * 문의를 지운다. <b>질문·답변 글이 행과 같이 사라진다</b>(청크 58).
     *
     * <p>표를 안 갈랐다. 다른 자유 텍스트는 <b>5년 표에 섞여 있어서</b> 떼어 냈는데
     * (`5i-2`) 문의는 그 기록 자체가 3년이라 뗄 자리가 없다 — 갈라도 수명이 같다.
     *
     * <p>기준은 <b>문의를 낸 날</b>이다. 답변일로 잡으면 답이 안 나간 문의가 영영 안 지워진다 —
     * 그 상태가 오래 남는 것이 바로 우리가 답을 안 한 경우다.
     */
    private int deleteExpiredInquiries(OffsetDateTime createdBefore) {
        return jdbc.sql("delete from inquiry where created_at < :createdBefore")
                .param("createdBefore", createdBefore)
                .update();
    }

    /**
     * 손해배상의 <b>사유 글</b>을 지운다(`43a-4b`). <b>판정 행은 그대로 남는다</b> —
     * {@code refund_note}·{@code order_status_history_note} 와 같은 모양이다.
     *
     * <p><b>넣는 코드보다 먼저 든다</b>({@link #deleteExpiredReturnPickups} 와 같은 자리).
     * 판정을 넣는 입구는 `43a-4c` 라 지금 이 표가 비어 있는데, 순서를 뒤집으면
     * <b>수집과 파기 사이가 위반 구간</b>이 된다(`D23`).
     *
     * <p>기준은 <b>정한 날</b>이다. 판정 행 자체가 결정이라 접수일과 판정일이 같다.
     */
    private int deleteExpiredCompensationNotes(OffsetDateTime decidedBefore) {
        return jdbc.sql("""
                        delete from compensation_note
                         where compensation_id in (
                             select compensation_id from compensation
                              where decided_at < :decidedBefore
                         )
                        """)
                .param("decidedBefore", decidedBefore)
                .update();
    }

    /**
     * 세법 보존이 끝난 정산 주기를 <b>통째로</b> 지운다(`43a-28b`, `D2` R41).
     *
     * <p><b>줄만 지우면 안 된다.</b> 지연 트리거 {@code settlement_amounts_check} 가 커밋 시점에
     * 정산서와 줄의 합계를 맞추므로, 줄을 빼고 정산서를 남기면 그 자리에서 막힌다.
     * 주기를 통째로 지우면 커밋 시점에 정산서 자체가 없어 트리거가 볼 행이 없다.
     *
     * <p><b>기준은 지급일이 아니라 주기의 거래일이다</b>({@code period_end}) —
     * {@link TaxRetention} 이 그 이유를 든다.
     *
     * <p><b>이월이 걸린 주기는 미룬다.</b> 다음 달 정산서의 줄이
     * {@code carried_from_settlement_id} 로 이 주기의 정산서를 가리키면 그 줄이 근거를 잃는다 —
     * 뒤 주기가 먼저 만료돼야 하고 그것은 한 달 뒤다.
     *
     * @return 지운 정산 주기 수
     */
    private int deleteExpiredSettlements(OffsetDateTime baseline) {
        List<Long> cycleIds = jdbc.sql("""
                        select sc.settlement_cycle_id, sc.period_end
                          from settlement_cycle sc
                         where not exists (
                                   select 1
                                     from settlement_item si
                                     join settlement s on s.settlement_id = si.settlement_id
                                    where si.carried_from_settlement_id is not null
                                      and s.settlement_cycle_id <> sc.settlement_cycle_id
                                      and si.carried_from_settlement_id in (
                                          select s2.settlement_id from settlement s2
                                           where s2.settlement_cycle_id = sc.settlement_cycle_id))
                        """)
                .query((rs, rowNum) -> new ExpiringCycle(
                        rs.getLong("settlement_cycle_id"), rs.getObject("period_end", LocalDate.class)))
                .list()
                .stream()
                .filter(cycle -> TaxRetention.retainUntil(cycle.periodEnd())
                        .isBefore(baseline.atZoneSameInstant(KST).toLocalDate()))
                .map(ExpiringCycle::cycleId)
                .toList();

        if (cycleIds.isEmpty()) {
            return 0;
        }

        // 자식부터다. cascade 를 파기 수단으로 쓰지 않는다(`D23`).
        jdbc.sql("""
                        delete from settlement_item
                         where settlement_id in (
                             select settlement_id from settlement
                              where settlement_cycle_id in (:ids))
                        """)
                .param("ids", cycleIds)
                .update();

        jdbc.sql("delete from settlement where settlement_cycle_id in (:ids)")
                .param("ids", cycleIds)
                .update();

        return jdbc.sql("delete from settlement_cycle where settlement_cycle_id in (:ids)")
                .param("ids", cycleIds)
                .update();
    }

    /** 만료를 재는 데 필요한 것만 담는다 */
    private record ExpiringCycle(long cycleId, LocalDate periodEnd) {}

    /**
     * 세법 보존이 끝난 손해배상 판정을 지운다(`43a-28b`, `D2` R41).
     *
     * <p><b>정산 줄이 가리키는 동안은 안 지운다.</b> 그 줄이 「무엇에 대한 배상인가」를 잃는다 —
     * 정산이 위에서 먼저 사라지므로 같은 회차에 이어진다.
     *
     * <p>사유 글({@code compensation_note})은 이미 없다. 3년에 먼저 사라진다.
     *
     * @return 지운 판정 수
     */
    private int deleteExpiredCompensations(OffsetDateTime baseline) {
        List<Long> ids = jdbc.sql("""
                        select c.compensation_id, (c.decided_at at time zone 'Asia/Seoul')::date as decided_on
                          from compensation c
                         where not exists (select 1 from settlement_item si
                                            where si.compensation_id = c.compensation_id)
                        """)
                .query((rs, rowNum) -> new ExpiringCompensation(
                        rs.getLong("compensation_id"), rs.getObject("decided_on", LocalDate.class)))
                .list()
                .stream()
                .filter(row -> TaxRetention.retainUntil(row.decidedOn())
                        .isBefore(baseline.atZoneSameInstant(KST).toLocalDate()))
                .map(ExpiringCompensation::compensationId)
                .toList();

        if (ids.isEmpty()) {
            return 0;
        }

        return jdbc.sql("delete from compensation where compensation_id in (:ids)")
                .param("ids", ids)
                .update();
    }

    /** 만료를 재는 데 필요한 것만 담는다 */
    private record ExpiringCompensation(long compensationId, LocalDate decidedOn) {}

    /**
     * 배송지를 지운다. <b>주문은 그대로 남는다</b> — 그게 분리해 둔 이유다(`D13`).
     *
     * <p>거래 사실(금액·상품명·일시)은 5년을 채우고 사람 정보만 먼저 사라진다.
     * R6(보존)과 R9(파기)가 부딪히는 자리를 테이블을 갈라서 푼 결과다.
     */
    private int deleteExpiredShipping(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("delete from order_shipping where order_id in (:ids)")
                .param("ids", orderIds)
                .update();
    }

    /**
     * 파기할 주문들의 후기 사진을 저장소와 표에서 지운다(`Q159`). 후기를 지우기 바로 앞에 부른다.
     *
     * <p><b>후기 사진은 공개 게시물이다</b>(사용자 선택) — 보유기간이 후기와 같아서 후기가 사라지는 이 자리가 그 끝이다.
     */
    private void purgeReviewImages(List<Long> orderIds) {
        record Keys(long reviewImageId, String objectKey, String thumbnailKey) {}

        List<Keys> keys = jdbc.sql("""
                        select ri.review_image_id, ri.object_key, ri.thumbnail_key
                          from review_image ri
                          join review r on r.review_id = ri.review_id
                         where r.order_item_id in (
                             select order_item_id from order_item
                              where seller_order_id in (
                                  select seller_order_id from seller_order where order_id in (:ids)))
                        """)
                .param("ids", orderIds)
                .query((rs, rowNum) -> new Keys(rs.getLong("review_image_id"),
                        rs.getString("object_key"), rs.getString("thumbnail_key")))
                .list();

        for (Keys key : keys) {
            images.delete(key.objectKey(), key.thumbnailKey());
        }
        if (!keys.isEmpty()) {
            jdbc.sql("delete from review_image where review_image_id in (:ids)")
                    .param("ids", keys.stream().map(Keys::reviewImageId).toList())
                    .update();
        }
    }

    /**
     * 보존 기간이 지난 주문을 지운다.
     *
     * <p><b>순서가 있다.</b> {@code order_item} → {@code seller_order} → {@code shop_order} 다 —
     * 참조가 전부 {@code restrict} 라 자식부터 지워야 한다. cascade 를 파기 수단으로 쓰지 않는다(`D23`).
     *
     * <p>{@code order_shipping} 은 이 시점에 이미 없다. 6개월에 먼저 사라진다.
     */
    private int deleteExpiredOrders(OffsetDateTime closedBefore) {
        List<Long> orderIds = purgeableOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        // 쓴 쿠폰이 주문을 restrict 로 잡는다(`49`). 쓴 쿠폰은 거래의 일부라 주문과 같이 간다 —
        // 할인액이 정산의 근거고(`51`), 근거만 남기고 주문을 지우면 그 정산서를 못 읽는다.
        //
        // **안 쓴 발급은 안 건드린다.** 주문을 안 가리키므로 여기 걸릴 것이 없다.
        jdbc.sql("delete from coupon_issue where used_order_id in (:ids)")
                .param("ids", orderIds)
                .update();

        // **후기 사진은 저장소까지 지운다**(`Q159`). 행은 후기의 cascade 로도 사라지지만 **cascade 를 파기
        // 수단으로 쓰지 않는다**(`D23`) — 그렇게 두면 공개 버킷에 주인 없는 사진이 남는다. 열쇠를 잃기 전에
        // 객체를 먼저 지우고 행을 지운다(`ImagePipeline.delete`). 이 트랜잭션이 뒤에서 실패하면 객체만 먼저
        // 사라진 행이 남는데, 보존기간이 끝나 곧 지워질 후기라 그쪽이 덜 나쁘다.
        purgeReviewImages(orderIds);

        // 후기가 주문 줄을 restrict 로 잡는다(`46`). 안 지우면 아래 delete 가 통째로 실패한다.
        //
        // **후기만 남기는 선택지가 없다.** `review.order_item_id` 가 `not null` 이라
        // 떼어 놓을 자리가 없고, 그것이 「산 사람만 쓴다」를 DB 가 드는 방식이다 —
        // 남기려고 널을 허용하면 그 강제 지점이 사라진다.
        jdbc.sql("""
                        delete from review
                         where order_item_id in (
                             select order_item_id from order_item
                              where seller_order_id in (
                                  select seller_order_id from seller_order where order_id in (:ids)))
                        """)
                .param("ids", orderIds)
                .update();

        jdbc.sql("""
                        delete from order_item
                         where seller_order_id in (
                             select seller_order_id from seller_order where order_id in (:ids))
                        """)
                .param("ids", orderIds)
                .update();

        // 상태 이력도 5년이다. 계약·청약철회 기록이라 주문과 같은 기간이고(`D2` R6),
        // 주문을 restrict 로 잡고 있어서 안 지우면 아래 delete 가 통째로 실패한다.
        jdbc.sql("""
                        delete from order_status_history
                         where order_id in (:ids)
                            or seller_order_id in (
                                select seller_order_id from seller_order where order_id in (:ids))
                        """)
                .param("ids", orderIds)
                .update();

        jdbc.sql("delete from seller_order where order_id in (:ids)")
                .param("ids", orderIds)
                .update();

        return jdbc.sql("delete from shop_order where order_id in (:ids)")
                .param("ids", orderIds)
                .update();
    }

    /**
     * 끝난 지 이만큼 지났고 <b>아무도 안 가리키는</b> 주문(`43a-28b`).
     *
     * <p>{@link #closedOrderIds} 와 가른 이유가 여기 있다. 배송지·수거지는 <b>정산이 걸려 있어도</b>
     * 여섯 달에 사라져야 한다 — 그쪽은 개인정보고 장부가 아니다. 미루는 것은 주문 축뿐이다.
     *
     * <p>세 참조가 {@code restrict} 라 남아 있으면 주문 삭제가 거부되고
     * <b>파기 회차 전체가 롤백된다.</b> 정산·배상·문의는 위에서 먼저 지우고,
     * 그래도 남은 것(보존 기간이 안 끝난 것)은 여기서 걸러 다음 회차로 넘긴다.
     *
     * <p><b>묶음 하나라도 걸리면 그 주문 전체가 빠진다.</b> 걸린 묶음만 빼고 주문을 지우면
     * 그 묶음이 남아서 {@code shop_order} 삭제가 다시 거부된다.
     */
    private List<Long> purgeableOrderIds(OffsetDateTime closedBefore) {
        return jdbc.sql("""
                        select b.order_id
                          from (select so.order_id, so.closed_at,
                                       (exists (select 1 from settlement_item si
                                                 where si.seller_order_id = so.seller_order_id
                                                    or si.order_item_id in (
                                                           select oi.order_item_id from order_item oi
                                                            where oi.seller_order_id = so.seller_order_id)
                                                    or si.refund_item_id in (
                                                           select ri.refund_item_id
                                                             from refund_item ri
                                                             join refund r on r.refund_id = ri.refund_id
                                                            where r.seller_order_id = so.seller_order_id))
                                        or exists (select 1 from compensation c
                                                    where c.seller_order_id = so.seller_order_id)
                                        or exists (select 1 from inquiry i
                                                    where i.seller_order_id = so.seller_order_id)) as blocked
                                  from seller_order so) b
                         group by b.order_id
                        having count(*) filter (where b.closed_at is null) = 0
                           and count(*) filter (where b.blocked) = 0
                           and max(b.closed_at) < :closedBefore
                        """)
                .param("closedBefore", closedBefore)
                .query(Long.class)
                .list();
    }

    /**
     * 끝난 지 이만큼 지난 주문. <b>주문 축 말고 그 주변(배송지·수거지·사유 글)을 지울 때 쓴다.</b>
     *
     * <p><b>셀러 주문이 하나라도 안 끝났으면 그 주문은 대상이 아니다.</b> 한 주문에 셀러가 여럿이면
     * 각자 따로 굴러가고(`D7`), 하나가 반품 중인데 배송지를 지우면 그 반품을 처리할 수 없다.
     *
     * <p>기산점이 {@code closed_at} 인 이유는 `D13` 이 "거래 종료일" 로 정해서다.
     *
     * <p><b>정산·배상·문의를 안 본다.</b> 그것을 보는 것은 {@link #purgeableOrderIds} 고,
     * 여기에 같은 조건을 붙이면 <b>정산이 걸린 주문의 배송지가 여섯 달에 안 지워진다</b>(`43a-28b`).
     */
    private List<Long> closedOrderIds(OffsetDateTime closedBefore) {
        return jdbc.sql("""
                        select so.order_id
                          from seller_order so
                         group by so.order_id
                        having count(*) filter (where so.closed_at is null) = 0
                           and max(so.closed_at) < :closedBefore
                        """)
                .param("closedBefore", closedBefore)
                .query(Long.class)
                .list();
    }

    /**
     * 지난 감사 로그를 지운다.
     *
     * <p>매일 도는 개인정보 파기가 아니라 여기 있다. 감사 로그는 양이 많고 급하지 않아서
     * 거래기록과 같은 월 단위로 훑는 편이 낫다.
     */
    private int deleteExpiredAuditLogs(OffsetDateTime createdBefore) {
        return jdbc.sql("delete from audit_log where created_at < :createdBefore")
                .param("createdBefore", createdBefore)
                .update();
    }

    /**
     * 수명이 끝난 배치 회차 이력을 지운다.
     *
     * <p><b>기준일로 센다.</b> {@code created_at} 이 아니라 그 회차가 무엇을 다룬 날이고,
     * 이력을 되짚는 물음이 언제나 「그날 그 배치가 돌았나」라서다(`D19`).
     *
     * <p>이 배치 자신의 회차 행도 대상이다. 1년 전 행이라 지금 회차는 안 걸린다.
     */
    private int deleteExpiredBatchRuns(OffsetDateTime baselineBefore) {
        return jdbc.sql("delete from batch_run where baseline_date < :baselineBefore")
                .param("baselineBefore", baselineBefore.toLocalDate())
                .update();
    }

    /**
     * 발행이 끝난 아웃박스 사건을 지운다. <b>안 보낸 것은 안 지운다</b>(`D13`, `33b`).
     *
     * <p>{@code published_at} 이 빈 행은 아직 아무도 못 받은 편지다 — 기간이 아무리 지나도
     * 그것을 지우면 그 사건은 영영 안 나간다. 조건이 <b>기간과 발행 여부 둘</b>인 이유다.
     *
     * <p><b>개인정보 파기가 아니다.</b> 페이로드에 식별자만 싣기로 정해 둬서
     * (`event-catalog.md` 「페이로드 규칙」) 여기 이름·연락처가 없다. 배치 이력을 얹은 것과 같은
     * 자리 — 수명이 끝난 행을 치우는 일이 같아서 같이 돈다.
     */
    private int deleteExpiredOutboxEvents(OffsetDateTime publishedBefore) {
        return jdbc.sql("""
                        delete from outbox_event
                        where published_at is not null and published_at < :publishedBefore
                        """)
                .param("publishedBefore", publishedBefore)
                .update();
    }

    /**
     * 개인화된 본문을 지운다. <b>메타는 남는다</b> — 그게 표를 갈라 둔 이유다(`D18-1`).
     *
     * <p>「보냈다」를 증명하는 것은 메타고, 본문은 그 위에 얹힌 개인정보 사본이다.
     * 문안은 템플릿 판에, 금액은 주문·결제에 남아서 <b>무슨 문안을 언제 누구에게 보냈나</b>는 복원된다.
     */
    /**
     * 환불 사유 글을 지운다. <b>환불은 그대로 남는다</b> 2014 그게 표를 가른 이유다(`5i-2`).
     *
     * <p>「무슨 사유로 몇 건」은 {@code reason_code} 가 답한다. 사라지는 것은
     * <b>사람이 쓴 글</b>뿐이고 거기 섞여 들어온 연락처도 같이 간다.
     *
     * <p>기간은 배송지·카드와 같은 여섯 달이다. 성격이 같은 개인정보 사본이라 값을 맞추면
     * 파기가 같은 기준 시각을 쓴다.
     */
    /**
     * 전이 사유 글을 지운다. <b>이력은 그대로 남는다</b>(`5i-3`).
     *
     * <p>「무엇이 무엇으로 바뀌었나」는 {@code from_status}·{@code to_status} 가 답한다.
     * 사라지는 것은 사람이 쓴 글뿐이고 거기 섞여 들어온 연락처도 같이 간다.
     */
    private int deleteExpiredHistoryNotes(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("""
                        delete from order_status_history_note
                         where order_status_history_id in (
                             select h.order_status_history_id from order_status_history h
                               left join seller_order so on so.seller_order_id = h.seller_order_id
                              where h.order_id in (:ids) or so.order_id in (:ids)
                         )
                        """)
                .param("ids", orderIds)
                .update();
    }

    private int deleteExpiredRefundNotes(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("""
                        delete from refund_note
                         where refund_id in (
                             select r.refund_id from refund r
                               join seller_order so on so.seller_order_id = r.seller_order_id
                              where so.order_id in (:ids)
                         )
                        """)
                .param("ids", orderIds)
                .update();
    }

    private int deleteExpiredNotificationBodies(OffsetDateTime createdBefore) {
        return jdbc.sql("""
                        delete from notification_body
                         where notification_id in (
                             select notification_id from notification where created_at < :createdBefore
                         )
                        """)
                .param("createdBefore", createdBefore)
                .update();
    }

    /**
     * 발송 이력을 지운다. <b>종류가 기간을 가른다</b>(`D18-1`).
     *
     * <p>거래 통지는 시행령 제6조제1항 2·3호라 5년이고 광고성 정보는 같은 항 1호라 여섯 달이다.
     * <b>박제해 둔 { kind} 로 고른다</b> — 판을 다시 읽으면 그 사이에 판이 고쳐졌을 때
     * 이미 나간 것의 보존 기간이 따라 움직인다.
     *
     * <p>본문은 위에서 이미 지웠고, 남은 것이 있어도 외래키가 { cascade} 라 같이 사라진다.
     */
    private int deleteExpiredNotifications(OffsetDateTime adsBefore, OffsetDateTime noticesBefore) {
        return jdbc.sql("""
                        delete from notification
                         where (kind = 'advertising'   and created_at < :adsBefore)
                            or (kind = 'transactional' and created_at < :noticesBefore)
                        """)
                .param("adsBefore", adsBefore)
                .param("noticesBefore", noticesBefore)
                .update();
    }

    /**
     * 카드 정보를 지운다. <b>결제 행은 그대로 남는다</b> — 배송지와 같은 구조다(`D2` R9).
     *
     * <p>금액·승인번호·수단·시각이 `payment` 에 남아서 <b>대금결제 기록은 5년을 채운다</b>.
     * 사라지는 것은 「어느 카드로 냈나」뿐이고, 그 물음은 분쟁과 함께 와서 여섯 달이면 닿는다.
     */
    private int deleteExpiredPaymentCards(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

        return jdbc.sql("""
                        delete from payment_card
                         where payment_id in (
                             select payment_id from payment where order_id in (:ids))
                        """)
                .param("ids", orderIds)
                .update();
    }
}
