package com.projectshop.shop.order;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;

/**
 * 사람이 안 눌러도 시각이 되면 옮겨지는 전이 둘(`D7`).
 *
 * <p><b>여기에 규칙이 없다.</b> 대상을 고르고 {@link OrderStatusService} 를 부를 뿐이다 —
 * 재고 복구도 이력도 그 서비스 안에 있다. 배치가 상태를 직접 갈아끼우면
 * 같은 규칙이 두 군데가 되고, 그때부터 배치로 바뀐 주문만 이력이 빈다.
 *
 * <p><b>건마다 트랜잭션이 갈린다.</b> 배치 메서드에 {@code @Transactional} 을 안 붙였다 —
 * 붙이면 하나가 터질 때 그날 처리한 것이 전부 롤백된다.
 *
 * <h2>여러 번 돌아도 결과가 같다</h2>
 *
 * 고르는 조건이 곧 처리 조건이다. 옮기고 나면 상태가 바뀌어서 다음 회차의 대상에서 빠진다.
 * 도중에 죽어도 처리된 것까지는 커밋돼 있고, 남은 것은 다음 회차가 집는다.
 */
@Component
public class OrderStatusBatch {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusBatch.class);

    /** 주문을 만들고 이만큼 안에 결제가 끝나야 한다(`D7`) */
    private static final Duration PAYMENT_DEADLINE = Duration.ofMinutes(30);

    private final JdbcClient jdbc;
    private final OrderStatusService statuses;

    OrderStatusBatch(JdbcClient jdbc, OrderStatusService statuses) {
        this.jdbc = jdbc;
        this.statuses = statuses;
    }

    /**
     * 결제 안 된 주문을 만료시킨다.
     *
     * <p><b>04:00 규칙에서 빠진다</b>(`D7`). `D10` 은 배치를 하루 한 번 04:00 에 돌리라고 하는데,
     * 그렇게 하면 재고가 하루 종일 잡혀 있다. 만료 판정은 <b>경과 시간</b>이라
     * 「전날 24시 기준」 같은 고정 기준이 필요 없고, 몇 시에 몇 번 돌든 결과가 같다.
     *
     * <p>{@code fixedDelay} 다. 앞 회차가 끝난 뒤부터 5분을 세므로 느려져도 겹치지 않는다.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void expireUnpaidOrders() {
        expireUnpaidOrders(OffsetDateTime.now());
    }

    /** @param baseline 이 시각 기준으로 30분이 지났는지 본다. 테스트가 시각을 넣는다 */
    public int expireUnpaidOrders(OffsetDateTime baseline) {
        OffsetDateTime cutoff = baseline.minus(PAYMENT_DEADLINE);

        List<Long> targets = jdbc.sql("""
                        select order_id from shop_order
                         where status = :pending and created_at < :cutoff
                         order by order_id
                        """)
                .param("pending", Payment.PAYMENT_PENDING.code())
                .param("cutoff", cutoff)
                .query(Long.class)
                .list();

        if (targets.isEmpty()) {
            // 대상이 없을 때까지 INFO 로 남기면 5분마다 한 줄씩 하루 288줄이 쌓여서
            // 진짜 처리가 그 사이에 묻힌다. 그래도 아무것도 안 남기면 "돌긴 도는가" 를 못 보므로
            // 한산한 회차는 DEBUG 로 내린다(`D16`).
            log.debug("결제 만료 배치 — 대상 없음 cutoff={}", cutoff);
            return 0;
        }
        log.info("결제 만료 배치 시작 cutoff={} 대상={}건", cutoff, targets.size());

        int moved = 0;
        for (long orderId : targets) {
            if (move(() -> statuses.movePayment(orderId, Payment.PAYMENT_EXPIRED,
                    Actor.system("결제 대기 %d분 초과".formatted(PAYMENT_DEADLINE.toMinutes()))),
                    "order_id=%d".formatted(orderId))) {
                moved++;
            }
        }
        log.info("결제 만료 배치 끝 처리={}건", moved);
        return moved;
    }

    /**
     * 자동확정 예정일이 지난 것을 확정한다.
     *
     * <p>배송완료 상태만 고른다. <b>반품이 접수돼 있으면 대상에서 빠진다</b>(`D7`) —
     * 상태가 이미 {@code return_requested} 라 이 조건에 안 걸린다.
     *
     * <p>기준일은 배송완료 때 박제한 값이다. 지금 계산하지 않는다 — 임시공휴일이 나중에 추가되면
     * 매번 계산하는 방식은 지난 기한까지 흔들린다(`D10`).
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void confirmDeliveredOrders() {
        confirmDeliveredOrders(OffsetDateTime.now());
    }

    /** @param baseline 이 시각을 지난 예정일을 대상으로 삼는다 */
    public int confirmDeliveredOrders(OffsetDateTime baseline) {
        List<Long> targets = jdbc.sql("""
                        select seller_order_id from seller_order
                         where status = :delivered and auto_confirm_at <= :baseline
                         order by seller_order_id
                        """)
                .param("delivered", Shipment.DELIVERED.code())
                .param("baseline", baseline)
                .query(Long.class)
                .list();

        if (targets.isEmpty()) {
            log.debug("자동 구매확정 배치 — 대상 없음 기준={}", baseline);
            return 0;
        }
        log.info("자동 구매확정 배치 시작 기준={} 대상={}건", baseline, targets.size());

        int moved = 0;
        for (long sellerOrderId : targets) {
            if (move(() -> statuses.moveShipment(sellerOrderId, Shipment.CONFIRMED,
                    Actor.system("자동 구매확정 예정일 경과")),
                    "seller_order_id=%d".formatted(sellerOrderId))) {
                moved++;
            }
        }
        log.info("자동 구매확정 배치 끝 처리={}건", moved);
        return moved;
    }

    /**
     * 하나가 실패해도 나머지를 계속한다.
     *
     * <p>레벨을 가른다. <b>전이표에 걸린 것은 `WARN`</b> 이다 — 고르고 나서 옮기기까지 사이에
     * 사람이 결제했거나 반품을 넣은 것이고, 사람이 할 일이 없다.
     * 그 밖의 실패는 `ERROR` 다 — 배치가 못 도는 것이라 누가 봐야 한다(`D16`).
     *
     * @param target 로그에 남길 식별자. 개인정보는 안 넣는다(`D16`)
     */
    private static boolean move(Runnable transition, String target) {
        try {
            transition.run();
            return true;
        } catch (ShopException e) {
            log.warn("배치가 건너뛴다 {} 이유={}", target, e.code());
            return false;
        } catch (RuntimeException e) {
            log.error("배치가 실패했다 {}", target, e);
            return false;
        }
    }
}
