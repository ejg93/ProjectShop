package com.projectshop.shop.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 법이 요구하는 거래 통지 넷을 보낸다(청크 56, `D2` R20).
 *
 * <p><b>조회는 통지가 아니다.</b> 네 시점 모두 화면은 이미 있었는데 <b>보내는 자리가 없었다</b> —
 * 제8조제3항이 「알리고, 언제든지 열람할 수 있게」로 둘을 나란히 적은 것이 그 증거다.
 * 열람만 되고 안 알리면 앞의 요건이 안 채워진다.
 *
 * <p><b>스위퍼다.</b> 상태를 훑어 <b>통지가 없는 건</b>을 찾는다 — {@code RefundSweeper} 와 같은 모양이고,
 * 같은 이유로 값을 한다: 상태 전이 코드를 안 건드리고, 한 회차가 죽어도 다음 회차가 집는다.
 * 전이 자리마다 발송을 심으면 그 자리 수만큼 빠뜨릴 곳이 생긴다.
 *
 * <p><b>같은 사건에 두 번 안 나가는 것은 여기서 안 막는다.</b> `54a` 의 부분 유니크가 막고,
 * {@code NotificationService} 가 그 예외를 「할 일 없음」으로 받는다. 스위퍼의 조건은
 * <b>대상을 줄이는 것</b>이지 중복을 막는 것이 아니다 — 조회와 발송 사이에 틈이 있어서다.
 */
@Component
public class NotificationSweeper {

    /**
     * 얼마나 지난 것까지 거슬러 보내나.
     *
     * <p><b>법이 정한 값이 아니라 우리가 정한 값이다.</b> 보내는 자리가 없던 동안 쌓인 주문에
     * 지금 와서 통지를 보내면 그것은 통지가 아니라 뒤늦은 무더기 발송이다.
     *
     * <p>이레면 배치가 일주일을 죽어 있어야 놓친다. 그보다 오래 멈춘 것은
     * 통지 하나가 아니라 운영이 문제고, 배치 회차 이력이 그 사실을 들고 있다(`D19`).
     */
    private static final int SWEEP_WINDOW_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(NotificationSweeper.class);

    private final JdbcClient jdbc;
    private final NotificationService notifications;

    NotificationSweeper(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /**
     * {@code fixedDelay} 다. 앞 회차가 끝난 뒤부터 5분을 세므로 느려져도 겹치지 않는다.
     *
     * <p><b>04:00 규칙에서 빠진다</b>(`D10`). 제14조제1항이 청약 확인을 「신속하게」 하라고 해서
     * 하루 한 번으로는 그 말을 못 지킨다.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT3M")
    public void sweep() {
        int sent = sweepAll(OffsetDateTime.now());
        if (sent == 0) {
            // 대상이 없는 회차를 INFO 로 남기면 5분마다 한 줄씩 쌓여서 진짜 처리가 묻힌다(`D16`).
            log.debug("거래 통지 스위퍼 — 대상 없음");
        } else {
            log.info("거래 통지 스위퍼 끝 발송={}건", sent);
        }
    }

    /**
     * 네 시점을 한 회차에 다 훑는다.
     *
     * @param now 기준 시각. 테스트가 시간을 통제하려고 넘긴다
     * @return 실제로 남긴 발송 수
     */
    public int sweepAll(OffsetDateTime now) {
        OffsetDateTime floor = now.minusDays(SWEEP_WINDOW_DAYS);

        return sweepOrderPlaced(floor)
                + sweepPaymentCompleted(floor)
                + sweepSupplyDelayed(floor)
                + sweepRefundCompleted(floor);
    }

    /** 청약 접수 확인. 제14조제1항 — 청약 의사표시의 수신 확인 */
    private int sweepOrderPlaced(OffsetDateTime floor) {
        List<Pending> targets = jdbc.sql("""
                        select o.user_id, o.order_id as target_id, o.order_number as first_value,
                               null as second_value
                          from shop_order o
                          left join notification n
                            on n.order_id = o.order_id and n.event_type = 'order_placed'
                         where o.created_at >= :floor and n.notification_id is null
                        """)
                .param("floor", floor)
                .query(Pending.class)
                .list();

        return send(targets, "order_placed", NotificationService.Target::order,
                target -> Map.of("order_number", target.firstValue()));
    }

    /** 대금 지급 통지. 제8조제3항 — 전자적 대금지급이 이루어진 사실을 알린다 */
    private int sweepPaymentCompleted(OffsetDateTime floor) {
        List<Pending> targets = jdbc.sql("""
                        select o.user_id, o.order_id as target_id, o.order_number as first_value,
                               p.amount::text as second_value
                          from payment p
                          join shop_order o on o.order_id = p.order_id
                          left join notification n
                            on n.order_id = p.order_id and n.event_type = 'payment_completed'
                         where p.status = 'approved' and p.created_at >= :floor
                           and n.notification_id is null
                        """)
                .param("floor", floor)
                .query(Pending.class)
                .list();

        return send(targets, "payment_completed", NotificationService.Target::order,
                target -> Map.of("order_number", target.firstValue(),
                        "amount", target.secondValue()));
    }

    /**
     * 공급 곤란 통지. 제15조제2항 전단 — 공급이 곤란한 사유를 알린다.
     *
     * <p><b>기한 초과 판정을 다시 안 짠다.</b> {@code seller_order_visible.is_ship_overdue} 가
     * 그 식을 들고 있다(`11c-2c`) — 여기서 같은 조건을 손으로 쓰면 사본이 둘이 되고,
     * 지연배상금이 걸린 판정이라 화면과 통지의 답이 갈리면 안 된다.
     */
    private int sweepSupplyDelayed(OffsetDateTime floor) {
        List<Pending> targets = jdbc.sql("""
                        select o.user_id, v.seller_order_id as target_id,
                               v.seller_order_number as first_value,
                               to_char(v.ship_due_at at time zone 'Asia/Seoul',
                                       'YYYY년 FMMM월 FMDD일 FMHH24시') as second_value
                          from seller_order_visible v
                          join shop_order o on o.order_id = v.order_id
                          left join notification n
                            on n.seller_order_id = v.seller_order_id
                           and n.event_type = 'supply_delayed'
                         where v.is_ship_overdue and v.shipped_at is null
                           and v.ship_due_at >= :floor and n.notification_id is null
                        """)
                .param("floor", floor)
                .query(Pending.class)
                .list();

        return send(targets, "supply_delayed", NotificationService.Target::sellerOrder,
                target -> Map.of("seller_order_number", target.firstValue(),
                        "ship_due_at", target.secondValue()));
    }

    /** 환급 통지. 제18조제3항 단서 — 환급에 필요한 조치를 하였음을 알린다 */
    private int sweepRefundCompleted(OffsetDateTime floor) {
        List<Pending> targets = jdbc.sql("""
                        select o.user_id, r.refund_id as target_id,
                               r.refund_number as first_value, r.amount::text as second_value
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join shop_order o on o.order_id = so.order_id
                          left join notification n
                            on n.refund_id = r.refund_id and n.event_type = 'refund_completed'
                         where r.status = 'approved' and r.decided_at >= :floor
                           and n.notification_id is null
                        """)
                .param("floor", floor)
                .query(Pending.class)
                .list();

        return send(targets, "refund_completed", NotificationService.Target::refund,
                target -> Map.of("refund_number", target.firstValue(),
                        "amount", target.secondValue()));
    }

    /**
     * 아직 통지가 안 나간 건.
     *
     * <p>네 질의가 칸 이름을 맞춰 쓴다. 사건마다 record 를 따로 두면 넷이 되는데
     * <b>담는 것이 「누구에게·무엇을 가리켜·무슨 값으로」로 같아서</b> 나눌 근거가 없다.
     *
     * @param userId      받는 사람
     * @param targetId    가리킬 자원의 id. 사건이 어느 표를 가리키는지는 부르는 쪽이 안다
     * @param firstValue  본문에 꽂을 첫 값. 대개 노출 번호다
     * @param secondValue 둘째 값. 없으면 {@code null}
     */
    private record Pending(long userId, long targetId, String firstValue, String secondValue) {}

    private int send(List<Pending> targets, String eventType,
            java.util.function.LongFunction<NotificationService.Target> target,
            java.util.function.Function<Pending, Map<String, String>> values) {
        int sent = 0;
        for (Pending pending : targets) {
            if (notifications.send(eventType, target.apply(pending.targetId()),
                    pending.userId(), values.apply(pending)).isPresent()) {
                sent++;
            }
        }
        return sent;
    }
}
