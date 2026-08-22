package com.projectshop.shop.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.RetryableBatch;

/**
 * 셀러가 손을 놓은 묶음을 닫는다(청크 10a-2, `D2` R9).
 *
 * <p><b>파기가 남의 행동에 매달려 있었다.</b> 보존 기간은 {@code seller_order.closed_at} 에서 흐르는데
 * 닫는 전이가 {@code confirmed}·{@code cancelled}·{@code returned} 와 결제 만료뿐이다 —
 * 셀러가 {@code preparing} 이나 {@code shipping} 에서 멈추면 <b>그 값이 영영 비고</b>,
 * 배송지·카드 정보·환불 사유 글이 무기한 남는다. 개인정보보호법 제21조와 정면으로 부딪치는데
 * <b>아무것도 안 깨진다.</b>
 *
 * <p><b>{@code return_requested} 는 안 민다</b>(사용자 선택). 제18조제2항 1호가 환급 기산점을
 * 「재화를 <b>반환받은 날</b>」로 잡아서, 물건이 안 왔는데 {@code returned} 로 닫으면
 * <b>「반환받았다」가 거짓이 된다</b> — 분쟁이 오면 우리 기록이 우리에게 불리한 증거가 된다.
 * 나머지 둘은 우리가 단정해도 되는 사실이라 다르다.
 *
 * <p><b>대신 그 구멍을 드러낸다.</b> 방치된 반품 요청이 있으면 회차가 그 수를 남긴다 —
 * 사람이 봐야 하는 일이라 자동으로 처리하지 않는다.
 */
@Component
public class StaleBundleBatch implements RetryableBatch {

    static final String BATCH_NAME = "stale_bundle_close";

    /**
     * 발송 기한을 넘기고 얼마를 더 기다리나.
     *
     * <p><b>법이 준 시계를 쓴다.</b> {@code ship_due_at} 은 약정 발송 기한이고(`11-6`),
     * 그것을 넘긴 것 자체는 이미 공급 곤란이라 통지가 나간다(`56`). 여기서 세는 이레는
     * <b>「통지를 받고도 안 움직였다」</b>를 가르는 여유고, 그 값은 우리가 정했다.
     */
    private static final int PREPARING_GRACE_DAYS = 7;

    /**
     * 보냈다고 한 뒤 배송완료가 안 오면 얼마 만에 닫나.
     *
     * <p><b>우리가 정한 값이다.</b> 국내 배송이 한 달을 넘길 이유가 없어서,
     * 그보다 오래 {@code shipping} 이면 물건이 아니라 <b>상태 갱신이 멈춘 것</b>으로 본다.
     *
     * <p>{@code delivered} 로 민다. 거기서 자동 구매확정이 이어받아 청약철회 기간이 흐르므로
     * <b>사는 사람의 권리가 안 줄어든다</b> — 바로 확정으로 밀면 철회할 틈이 사라진다.
     */
    private static final int SHIPPING_STALE_DAYS = 30;

    /** 방치된 반품 요청을 「오래됐다」고 보는 선. 처리는 안 하고 세기만 한다 */
    private static final int RETURN_STALE_DAYS = 30;

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(StaleBundleBatch.class);

    private final JdbcClient jdbc;
    private final OrderStatusService statuses;
    private final BatchRuns runs;

    StaleBundleBatch(JdbcClient jdbc, OrderStatusService statuses, BatchRuns runs) {
        this.jdbc = jdbc;
        this.statuses = statuses;
        this.runs = runs;
    }

    /**
     * 04:00 규칙을 따른다(`D10`). 하루가 늦어도 되는 일이라 분 단위로 훑을 이유가 없다.
     *
     * <p>자동 구매확정(04:00)보다 <b>뒤에 둔다</b>. 앞에 두면 그날 확정될 건을
     * 이 배치가 먼저 보고 「아직 안 닫혔다」로 세는 회차가 생긴다.
     */
    @Scheduled(cron = "0 45 4 * * *", zone = "Asia/Seoul")
    public void close() {
        close(LocalDate.now(KST));
    }

    /** 그날 회차를 돌리고 이력을 남긴다(`36a`) */
    public void close(LocalDate baselineDate) {
        runs.record(BATCH_NAME, baselineDate, () -> {
            int closed = close(OffsetDateTime.now());
            return BatchRuns.Counts.of(closed);
        });
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public void runFor(LocalDate baselineDate) {
        close(baselineDate);
    }

    /**
     * 방치된 묶음을 닫는다.
     *
     * @param baseline 이 시각에서 유예를 뺀 것보다 오래된 묶음이 대상이다
     * @return 실제로 닫은 수
     */
    public int close(OffsetDateTime baseline) {
        int cancelled = cancelUnshipped(baseline.minusDays(PREPARING_GRACE_DAYS));
        int delivered = markDelivered(baseline.minusDays(SHIPPING_STALE_DAYS));

        warnStaleReturns(baseline.minusDays(RETURN_STALE_DAYS));

        int closed = cancelled + delivered;
        if (closed == 0) {
            log.debug("방치 묶음 마감 — 대상 없음 기준={}", baseline);
        } else {
            log.info("방치 묶음 마감 끝 취소={}건 배송완료={}건", cancelled, delivered);
        }
        return closed;
    }

    /**
     * 발송 기한을 한참 넘긴 묶음을 취소한다.
     *
     * <p><b>취소가 답인 이유는 법에 있다.</b> 제15조제2항이 공급이 곤란하면 사유를 알리고
     * 환급하라고 한다 — 안 보낸 것을 계속 열어 두는 것은 그 요구를 미루는 것이다.
     *
     * <p><b>환불은 따라온다.</b> 닫히는 순간 {@code closed_at} 이 채워지고
     * {@code RefundSweeper} 가 요청이 없는 묶음에 요청을 만든다(`12a-3`) —
     * 여기서 환불을 직접 만들면 그 스위퍼와 사본이 둘이 된다.
     */
    private int cancelUnshipped(OffsetDateTime dueBefore) {
        List<Long> targets = jdbc.sql("""
                        select seller_order_id from seller_order
                         where status = 'preparing'
                           and ship_due_at is not null and ship_due_at < :dueBefore
                         order by seller_order_id
                        """)
                .param("dueBefore", dueBefore)
                .query(Long.class)
                .list();

        return move(targets, Shipment.CANCELLED, "발송 기한 경과로 자동 취소");
    }

    /**
     * 보냈다고 한 뒤 갱신이 멈춘 묶음을 배송완료로 민다.
     *
     * <p><b>확정으로 바로 안 민다.</b> 청약철회 기간이 배송완료에서 시작하므로
     * 확정으로 보내면 사는 사람이 철회할 틈이 사라진다 — 여기서 미는 것은
     * <b>기록을 실제에 맞추는 일</b>이지 거래를 끝내는 일이 아니다.
     */
    private int markDelivered(OffsetDateTime shippedBefore) {
        List<Long> targets = jdbc.sql("""
                        select seller_order_id from seller_order
                         where status = 'shipping'
                           and shipped_at is not null and shipped_at < :shippedBefore
                         order by seller_order_id
                        """)
                .param("shippedBefore", shippedBefore)
                .query(Long.class)
                .list();

        return move(targets, Shipment.DELIVERED, "배송 상태 갱신 없이 기간 경과");
    }

    /**
     * 방치된 반품 요청을 센다. <b>처리는 안 한다.</b>
     *
     * <p>물건이 돌아왔는지를 우리가 모르므로 자동으로 닫을 수 없다. 대신 세어서 남긴다 —
     * <b>안 세면 이 구멍이 아무 데도 안 드러나고</b>, 그 사이 그 주문의 개인정보는 계속 남는다.
     */
    private void warnStaleReturns(OffsetDateTime requestedBefore) {
        int stale = jdbc.sql("""
                        select count(*) from seller_order
                         where status = 'return_requested' and updated_at < :requestedBefore
                        """)
                .param("requestedBefore", requestedBefore)
                .query(Integer.class)
                .single();

        if (stale > 0) {
            // 사람이 봐야 하는 일이라 `WARN` 이다. 자동으로 닫으면 「반환받았다」가 거짓이 된다.
            log.warn("반품 요청이 오래 열려 있다 대상={}건 — 물건이 돌아왔는지 확인이 필요하다", stale);
        }
    }

    /**
     * 하나가 실패해도 나머지를 계속한다.
     *
     * <p>고르고 나서 옮기기까지 사람이 발송했거나 취소했을 수 있다. 그때 전이표가 막는 것은
     * <b>사고가 아니라 정상</b>이라 회차를 실패로 만들지 않는다({@code OrderStatusBatch} 와 같은 판단).
     */
    private int move(List<Long> targets, Shipment to, String reason) {
        int moved = 0;
        for (long sellerOrderId : targets) {
            try {
                statuses.moveShipment(sellerOrderId, to, Actor.system(reason));
                moved++;
            } catch (RuntimeException e) {
                log.warn("방치 묶음 마감 건너뜀 seller_order_id={} 이유={}", sellerOrderId,
                        e.getClass().getSimpleName());
            }
        }
        return moved;
    }
}
