package com.projectshop.shop.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final JdbcClient jdbc;

    TransactionPurgeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param shippingAddresses 지운 배송지 행 수
     * @param paymentCards      지운 카드 정보 행 수. 결제 행은 남는다
     * @param orders            보존 기간이 지나 지운 주문 수
     * @param auditLogs         보존 기간이 지나 지운 감사 로그 수
     * @param batchRuns         보존 기간이 지나 지운 배치 회차 이력 수
     * @param notificationBodies 보존 기간이 지나 지운 발송 본문 수. 메타는 남는다
     * @param notifications     보존 기간이 지나 지운 발송 이력 수
     */
    public record Purged(int shippingAddresses, int paymentCards, int orders,
            int auditLogs, int batchRuns, int notificationBodies, int notifications) {}

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
        int orders = deleteExpiredOrders(baseline.minusYears(ORDER_YEARS));
        int auditLogs = deleteExpiredAuditLogs(baseline.minusYears(AUDIT_YEARS));
        int batchRuns = deleteExpiredBatchRuns(baseline.minusYears(BATCH_RUN_YEARS));
        int notificationBodies =
                deleteExpiredNotificationBodies(baseline.minusMonths(NOTIFICATION_BODY_MONTHS));
        int notifications = deleteExpiredNotifications(
                baseline.minusMonths(ADVERTISEMENT_MONTHS), baseline.minusYears(ORDER_YEARS));

        return new Purged(shippingAddresses, paymentCards, orders, auditLogs, batchRuns,
                notificationBodies, notifications);
    }

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
     * 보존 기간이 지난 주문을 지운다.
     *
     * <p><b>순서가 있다.</b> {@code order_item} → {@code seller_order} → {@code shop_order} 다 —
     * 참조가 전부 {@code restrict} 라 자식부터 지워야 한다. cascade 를 파기 수단으로 쓰지 않는다(`D23`).
     *
     * <p>{@code order_shipping} 은 이 시점에 이미 없다. 6개월에 먼저 사라진다.
     */
    private int deleteExpiredOrders(OffsetDateTime closedBefore) {
        List<Long> orderIds = closedOrderIds(closedBefore);
        if (orderIds.isEmpty()) {
            return 0;
        }

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
     * 끝난 지 이만큼 지난 주문.
     *
     * <p><b>셀러 주문이 하나라도 안 끝났으면 그 주문은 대상이 아니다.</b> 한 주문에 셀러가 여럿이면
     * 각자 따로 굴러가고(`D7`), 하나가 반품 중인데 배송지를 지우면 그 반품을 처리할 수 없다.
     *
     * <p>기산점이 {@code closed_at} 인 이유는 `D13` 이 "거래 종료일" 로 정해서다.
     * 채우는 것은 청크 11 이라 지금은 대상이 안 잡힌다 — 로직과 테스트가 먼저 선 상태다.
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
     * 개인화된 본문을 지운다. <b>메타는 남는다</b> — 그게 표를 갈라 둔 이유다(`D18-1`).
     *
     * <p>「보냈다」를 증명하는 것은 메타고, 본문은 그 위에 얹힌 개인정보 사본이다.
     * 문안은 템플릿 판에, 금액은 주문·결제에 남아서 <b>무슨 문안을 언제 누구에게 보냈나</b>는 복원된다.
     */
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
