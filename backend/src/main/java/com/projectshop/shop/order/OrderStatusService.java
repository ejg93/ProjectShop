package com.projectshop.shop.order;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 주문의 상태를 옮기는 유일한 자리(`D7`).
 *
 * <p>{@code status} 를 여기저기서 갈아끼우면 반드시 깨진다. 전이표를 거치지 않는 변경이 하나라도
 * 있으면 <b>표를 읽어도 실제로 무슨 일이 일어나는지 알 수 없다.</b>
 *
 * <p>전이가 상태만 바꾸지 않는다. 배송완료는 기한을 박제하고, 끝난 거래는 파기 기산점을 채우고,
 * 취소는 재고를 되돌린다. <b>그 곁가지를 호출자에게 맡기면 한 군데서 빠뜨린다</b> —
 * 빠뜨린 것이 재고면 팔 수 있는 물건이 사라지고, 기산점이면 5년이 지나도 안 지워진다.
 *
 * <p><b>누가 옮길 수 있는지는 안 본다.</b> 그건 권한 축이고 청크 11a 가 붙인다.
 * 여기서는 행위자를 이력에 적기만 한다.
 */
@Service
public class OrderStatusService {

    /** 청약철회 기간. 배송완료 다음날부터 센다(`D2` R3·`D10`) */
    private static final int WITHDRAWAL_DAYS = 7;

    /**
     * 자동 구매확정까지의 기간(`D7`).
     *
     * <p>청약철회 7일 바로 다음날이다. <b>더 짧으면 반품할 수 있는 주문이 확정</b>돼서
     * 정산 대상에 들어간다(`D10`).
     */
    private static final int AUTO_CONFIRM_DAYS = 8;

    /**
     * 말일의 마지막 순간. <b>저장 정밀도에 맞춘 값이다</b>(`D10` 「저장 정밀도가 마이크로초다」).
     *
     * <p>Postgres 가 마이크로초 아래를 올리므로 나노초를 채우면 날짜가 하루 넘어간다.
     */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_999_000);

    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;

    OrderStatusService(JdbcClient jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    /**
     * 누가 옮겼나. 이력에 그대로 들어간다(`V18`).
     *
     * @param userId 사람이 옮겼으면 누구인지. {@code system} 은 지목할 사람이 없어 {@code null} 이다
     * @param reason 관리자 강제 전이의 근거. 관리자면 필수다(`D7`)
     */
    public record Actor(String type, Long userId, String reason) {

        public static final String SYSTEM = "system";

        /** 배치와 결제 모듈이 쓴다 */
        public static Actor system(String reason) {
            return new Actor(SYSTEM, null, reason);
        }

        public static Actor person(String type, long userId) {
            return new Actor(type, userId, null);
        }

        public static Actor admin(long userId, String reason) {
            return new Actor("admin", userId, reason);
        }
    }

    /**
     * 결제 상태를 옮긴다.
     *
     * <p>결제가 끝내 안 되면 그 주문의 셀러 주문도 같이 닫는다 — 안 닫으면
     * <b>영원히 안 끝나는 셀러 주문</b>이 남고, 거래 종료 시각이 없어서 파기 대상으로도 안 잡힌다.
     */
    @Transactional
    public void movePayment(long orderId, Payment to, Actor actor) {
        Payment from = Payment.of(currentPaymentStatus(orderId));
        require(OrderTransitions.allows(from, to), from.code(), to.code(), actor);

        jdbc.sql("update shop_order set status = :status where order_id = :orderId")
                .param("status", to.code())
                .param("orderId", orderId)
                .update();

        recordOrderHistory(orderId, from, to, actor);

        if (to == Payment.PAYMENT_EXPIRED || to == Payment.PAYMENT_FAILED) {
            cancelSellerOrdersOf(orderId, to);
        }
    }

    /**
     * 배송 상태를 옮긴다.
     *
     * <p>곁가지가 셋 붙는다.
     * <ul>
     *   <li>배송완료 — 청약철회 만료일과 자동확정 예정일을 <b>그 자리에서 박제한다</b>(`D10`).
     *       나중에 계산하면 임시공휴일이 추가됐을 때 지난 기한까지 흔들린다</li>
     *   <li>거래 종료(확정·취소·반품완료) — {@code closed_at} 을 채운다. 파기 기산점이다(`D13`)</li>
     *   <li>취소 — 재고를 되돌린다</li>
     * </ul>
     */
    @Transactional
    public void moveShipment(long sellerOrderId, Shipment to, Actor actor) {
        Shipment from = Shipment.of(currentShipmentStatus(sellerOrderId));
        require(OrderTransitions.allows(from, to), from.code(), to.code(), actor);

        applyShipment(sellerOrderId, from, to, actor);
    }

    private void applyShipment(long sellerOrderId, Shipment from, Shipment to, Actor actor) {
        if (to == Shipment.RETURN_REQUESTED) {
            requireWithdrawable(sellerOrderId);
        }

        if (to == Shipment.CANCELLED) {
            // 되돌리기 전에 옮긴다. 상태가 먼저 바뀌어야 같은 셀러 주문을 두 번 취소하는 요청이
            // 두 번째에 전이표에 걸린다 — 안 그러면 재고가 두 번 늘어난다.
            updateShipmentStatus(sellerOrderId, to);
            restoreStock(sellerOrderId);
        } else {
            updateShipmentStatus(sellerOrderId, to);
        }

        if (to == Shipment.DELIVERED) {
            freezeDeadlines(sellerOrderId);
        }
        if (to == Shipment.CONFIRMED || to == Shipment.CANCELLED || to == Shipment.RETURNED) {
            closeTransaction(sellerOrderId);
        }

        recordSellerOrderHistory(sellerOrderId, from, to, actor);
    }

    /**
     * 결제가 끝내 안 된 주문의 셀러 주문을 닫는다.
     *
     * <p>이미 배송이 시작된 것은 건드리지 않는다. 결제 전에 물건이 나갔다면 그건
     * 상태를 고쳐서 될 일이 아니라 사람이 볼 일이다 — 전이표가 막고 있으니 여기 올 수 없다.
     */
    private void cancelSellerOrdersOf(long orderId, Payment reason) {
        List<Long> pending = jdbc.sql("""
                        select seller_order_id from seller_order
                         where order_id = :orderId and status = :preparing
                         order by seller_order_id
                        """)
                .param("orderId", orderId)
                .param("preparing", Shipment.PREPARING.code())
                .query(Long.class)
                .list();

        for (long sellerOrderId : pending) {
            applyShipment(sellerOrderId, Shipment.PREPARING, Shipment.CANCELLED,
                    Actor.system("결제가 %s 로 끝나 자동 취소".formatted(reason.code())));
        }
    }

    /**
     * 이 묶음을 반품 접수할 수 있나.
     *
     * <p><b>도메인 규칙이라 여기 있다</b>(`permission-rules.md` 「상태 축」). 근거가 주문 상태가
     * 아니라 기한과 상품 속성이고, 틀렸을 때 생기는 일이 "남의 것을 본다" 가 아니라
     * "반품이 잘못 돈다" 다. 전이를 부르는 모든 입구가 여기를 지난다.
     *
     * <p>둘을 본다.
     * <ul>
     *   <li><b>기한</b> — 청약철회는 7일이다(`D2` R3, 전자상거래법 제17조). 배송완료 때 박제해 둔
     *       값을 읽는다. 여기서 다시 계산하면 그 사이 임시공휴일이 추가됐을 때
     *       지나간 주문의 기한까지 흔들린다(`D10`)</li>
     *   <li><b>제한 상품</b> — 제17조제2항이 정한 사유다(`D2` R4). 하나라도 걸리면 묶음 전체를
     *       막는다. 취소·반품의 최소 단위가 셀러 묶음이라(`D7`) 항목별로 못 가른다</li>
     * </ul>
     *
     * <p><b>제한 사유 셋을 다 막는다.</b> {@code digital_content}(제17조제2항 5호)는 "제공이 개시된"
     * 사건이 있어야 성립하는데, 반품 접수는 {@code delivered} 에서만 열려서
     * (`OrderStatusPolicy`) 여기 오는 묶음은 이미 재화가 공급된 것이다 —
     * 개시 여부를 따로 담을 컬럼이 필요 없다.
     *
     * <p><b>하자 반품은 아직 이 경로에 없다.</b> 제17조제3항은 3개월인데 접수가 사유를 안 받으므로
     * 지금 들어오는 것은 전부 단순 변심으로 볼 수밖에 없다. 사유를 받는 것은 반품 축(43·44)이고
     * 그때 이 검사에 갈래가 생긴다.
     */
    private void requireWithdrawable(long sellerOrderId) {
        // 기한이 비어 있으면 막을 근거가 없다. 배송완료를 안 지난 묶음인데 그건 전이표가 이미 막는다.
        OffsetDateTime expireAt = jdbc.sql("""
                        select withdrawal_expire_at from seller_order
                         where seller_order_id = :sellerOrderId
                        """)
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> rs.getObject("withdrawal_expire_at", OffsetDateTime.class))
                .single();

        if (expireAt != null && expireAt.isBefore(OffsetDateTime.now())) {
            throw new ShopException(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED,
                    "청약철회 기간이 %s 에 끝났다".formatted(expireAt));
        }

        String restriction = jdbc.sql("""
                        select p.withdrawal_restriction_reason
                          from order_item oi
                          join sku s     on s.sku_id = oi.sku_id
                          join product p on p.product_id = s.product_id
                         where oi.seller_order_id = :sellerOrderId
                           and p.is_withdrawal_restricted
                         order by oi.order_item_id
                         limit 1
                        """)
                .param("sellerOrderId", sellerOrderId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (restriction != null) {
            throw new ShopException(ErrorCode.WITHDRAWAL_RESTRICTED,
                    "청약철회가 제한된 상품이 들어 있다: " + restriction);
        }
    }

    /**
     * 취소된 셀러 주문의 재고를 되돌린다.
     *
     * <p><b>`sku_id` 오름차순이다</b>(`D11`). 주문 생성이 같은 순서로 잠그므로 순환이 안 생긴다.
     *
     * <p>반품(`RETURNED`)은 여기 없다. 물건이 돌아와 다시 팔 수 있는지는 검수 결과라
     * 상태 전이만으로 못 정한다 — 반품 축(청크 43·44)이 그것을 다룬다.
     */
    private void restoreStock(long sellerOrderId) {
        List<Long[]> items = jdbc.sql("""
                        select sku_id, quantity from order_item
                         where seller_order_id = :sellerOrderId
                         order by sku_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> new Long[] {rs.getLong("sku_id"), rs.getLong("quantity")})
                .list();

        for (Long[] item : items) {
            jdbc.sql("update sku set stock_count = stock_count + :quantity where sku_id = :skuId")
                    .param("quantity", item[1])
                    .param("skuId", item[0])
                    .update();
        }
    }

    /**
     * 배송완료 시점에 기한 둘을 박제한다.
     *
     * <p>초일을 안 넣고 말일 24시에 끝난다. 말일이 쉬는 날이면 다음 영업일로 민다 —
     * 기간이 사용자에게 유리한 쪽으로 늘어난다(`D10`).
     */
    private void freezeDeadlines(long sellerOrderId) {
        LocalDate deliveredOn = LocalDate.now(BusinessCalendar.ZONE);

        LocalDate withdrawalLastDay = calendar.nextBusinessDay(deliveredOn.plusDays(WITHDRAWAL_DAYS));
        LocalDate autoConfirmLastDay = autoConfirmLastDay(deliveredOn, withdrawalLastDay);

        jdbc.sql("""
                        update seller_order
                           set delivered_at         = now(),
                               withdrawal_expire_at = :withdrawal,
                               auto_confirm_at      = :autoConfirm
                         where seller_order_id = :sellerOrderId
                        """)
                .param("withdrawal", endOfDay(withdrawalLastDay))
                .param("autoConfirm", endOfDay(autoConfirmLastDay))
                .param("sellerOrderId", sellerOrderId)
                .update();
    }

    /**
     * 자동확정일.
     *
     * <p>기본은 배송완료 다음날부터 8일째다. 다만 <b>말일 보정이 두 기한을 같은 날로 붙일 수 있다</b> —
     * 청약철회 말일이 쉬는 날이라 하루 밀리면 8일째와 겹친다. 그러면 청약철회가 살아 있는 날
     * 자정에 자동확정 배치가 돌아서, 아직 반품할 수 있는 주문이 확정된다.
     *
     * <p>그래서 <b>청약철회 만료 다음날보다 앞설 수 없다</b>. `D10` 이 정한 것은 8일이라는 날수가 아니라
     * "청약철회 기간보다 짧으면 안 된다" 는 제약이고, 겹치는 날은 그 제약이 이긴다.
     */
    LocalDate autoConfirmLastDay(LocalDate deliveredOn, LocalDate withdrawalLastDay) {
        LocalDate byCount = deliveredOn.plusDays(AUTO_CONFIRM_DAYS);
        LocalDate afterWithdrawal = withdrawalLastDay.plusDays(1);

        return calendar.nextBusinessDay(byCount.isAfter(afterWithdrawal) ? byCount : afterWithdrawal);
    }

    /**
     * 말일 24시. 기간은 날짜 단위라 시각으로 안 센다(`D10`).
     *
     * <p><b>{@code LocalTime.of(23, 59, 59, 999_999_000)} 를 안 쓴다.</b> 그건 나노초까지(`.999999999`)인데
     * Postgres {@code timestamptz} 는 마이크로초까지만 담고 나머지를 <b>올린다</b> —
     * 저장되면 말일이 아니라 <b>다음날 {@code 00:00:00}</b> 이 된다.
     *
     * <p>시각으로 비교하는 코드는 그래도 멀쩡해서 늦게 드러난다. 저장된 값을 다시 날짜로
     * 되돌리는 자리만 한 칸 밀리고, 그게 말일이 금요일인 날에만 테스트를 깨뜨렸다(`stack.md`).
     */
    private static OffsetDateTime endOfDay(LocalDate lastDay) {
        return lastDay.atTime(END_OF_DAY).atZone(BusinessCalendar.ZONE).toOffsetDateTime();
    }

    /** 거래가 끝난 시각. 보존 기간이 여기서부터 흐른다(`D13`) */
    private void closeTransaction(long sellerOrderId) {
        jdbc.sql("update seller_order set closed_at = now() where seller_order_id = :sellerOrderId")
                .param("sellerOrderId", sellerOrderId)
                .update();
    }

    private void updateShipmentStatus(long sellerOrderId, Shipment to) {
        jdbc.sql("update seller_order set status = :status where seller_order_id = :sellerOrderId")
                .param("status", to.code())
                .param("sellerOrderId", sellerOrderId)
                .update();
    }

    /**
     * 전이표에 없으면 막는다.
     *
     * <p><b>관리자는 표 밖으로도 옮긴다</b>(`D7`). CS 처리에 필요해서고, 대신 사유가 남는다 —
     * 정상 경로가 아니라서 왜 그랬는지가 없으면 나중에 데이터가 왜 이 모양인지 아무도 모른다.
     * 사유가 비어 있으면 강제 전이로 안 쳐 준다.
     */
    private static void require(boolean allowed, String from, String to, Actor actor) {
        if (allowed) {
            return;
        }
        boolean forcedByAdmin = "admin".equals(actor.type())
                && actor.reason() != null && !actor.reason().isBlank();

        if (!forcedByAdmin) {
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "%s → %s 는 전이표에 없다".formatted(from, to));
        }
    }

    private String currentPaymentStatus(long orderId) {
        return jdbc.sql("select status from shop_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }

    private String currentShipmentStatus(long sellerOrderId) {
        return jdbc.sql("select status from seller_order where seller_order_id = :sellerOrderId")
                .param("sellerOrderId", sellerOrderId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND));
    }

    private void recordOrderHistory(long orderId, Payment from, Payment to, Actor actor) {
        recordHistory(orderId, null, from.code(), to.code(), actor);
    }

    private void recordSellerOrderHistory(long sellerOrderId, Shipment from, Shipment to, Actor actor) {
        recordHistory(null, sellerOrderId, from.code(), to.code(), actor);
    }

    private void recordHistory(Long orderId, Long sellerOrderId, String from, String to, Actor actor) {
        jdbc.sql("""
                        insert into order_status_history (order_id, seller_order_id, from_status, to_status,
                                                          actor_type, actor_user_id, reason)
                        values (:orderId, :sellerOrderId, :from, :to, :actorType, :actorUserId, :reason)
                        """)
                .param("orderId", orderId)
                .param("sellerOrderId", sellerOrderId)
                .param("from", from)
                .param("to", to)
                .param("actorType", actor.type())
                .param("actorUserId", actor.userId())
                .param("reason", actor.reason())
                .update();
    }
}
