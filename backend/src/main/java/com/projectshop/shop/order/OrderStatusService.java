package com.projectshop.shop.order;

import java.time.LocalDate;
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
     * 하자 반품의 기간(`D2` R3, 전자상거래법 제17조제3항).
     *
     * <p><b>영업일이 아니라 역일이다.</b> 조문이 「3개월」이라고 하고 영업일이라는 말이 없다 —
     * 7일·8일과 달리 {@link BusinessCalendar} 를 안 태우는 이유가 이것이다.
     */
    private static final int DEFECT_WITHDRAWAL_MONTHS = 3;

    /**
     * 반품 사유의 종류(`V29`). <b>어느 조항으로 받는지가 여기서 갈린다.</b>
     *
     * <p>제17조제3항이 「제1항 및 제2항에도 불구하고」로 시작해서, 하자 반품은 7일 기한도
     * 청약철회 제한도 안 걸린다. 사유를 안 받으면 들어오는 것을 전부 단순 변심으로 볼 수밖에 없고
     * 그러면 <b>8일째 하자 신고가 거부된다</b>.
     */
    public enum ReturnReason {

        /** 제17조제1항. 7일이고 제한 사유가 걸린다 */
        CHANGE_OF_MIND("change_of_mind"),

        /** 제17조제3항. 표시·광고와 다르거나 계약과 다르게 이행된 경우. 3개월이고 제한이 안 걸린다 */
        DEFECT("defect");

        private final String code;

        ReturnReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

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

        if (to == Payment.PAID) {
            freezeShipDeadline(orderId);
        }
        if (to == Payment.PAYMENT_EXPIRED || to == Payment.PAYMENT_FAILED) {
            cancelSellerOrdersOf(orderId, to);
        }
    }

    /**
     * 결제가 승인된 순간 발송 기한을 박제한다(`D2` R21, 전자상거래법 제15조제1항).
     *
     * <p><b>선지급식이라 기산점이 대금을 지급한 날이다.</b> 법이 3영업일을 정했고,
     * 공급시기를 따로 약정한 상품은 그 날수를 쓴다(제15조제1항 단서) —
     * 주문 시점에 묶음에 박제해 둔 {@code supply_lead_days} 가 그 값이다(`V26`).
     *
     * <p><b>지금 계산하고 다시 안 센다.</b> 조회할 때마다 세면 임시공휴일이 나중에 추가됐을 때
     * 지나간 주문의 기한까지 흔들린다(`D10`) — {@link #freezeDeadlines} 와 같은 이유다.
     *
     * <p>한 주문의 묶음이 여럿이면 <b>각자 다른 기한을 갖는다.</b> 약정이 상품마다 다르고
     * 묶음이 셀러마다 나가므로 하나로 묶을 근거가 없다.
     */
    private void freezeShipDeadline(long orderId) {
        LocalDate paidOn = LocalDate.now(BusinessCalendar.ZONE);

        List<Long[]> bundles = jdbc.sql("""
                        select seller_order_id, supply_lead_days from seller_order
                         where order_id = :orderId
                         order by seller_order_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Long[] {
                        rs.getLong("seller_order_id"), (long) rs.getInt("supply_lead_days")})
                .list();

        for (Long[] bundle : bundles) {
            jdbc.sql("""
                            update seller_order set ship_due_at = :dueAt
                             where seller_order_id = :sellerOrderId
                            """)
                    .param("dueAt", BusinessCalendar.endOfDay(
                            calendar.plusBusinessDays(paidOn, bundle[1].intValue())))
                    .param("sellerOrderId", bundle[0])
                    .update();
        }
    }

    /**
     * 결제가 승인됐다. <b>결제 모듈이 부르는 입구다</b>(`D7` 「누가 옮기나」).
     *
     * <p>{@link #movePayment} 를 안 열고 이 둘만 여는 이유는 <b>결제 상태의 뜻이 이 패키지 것</b>이라서다
     * (`D23` 「남의 자원 표를 언제 직접 만지나」). 밖에서 전이 대상을 골라 넘기게 하면
     * 결제 모듈이 "무엇에서 무엇으로 갈 수 있나" 를 같이 알게 되고, 그 표가 두 군데가 된다.
     *
     * <p>행위자가 {@code system} 이다. 사람이 옮기는 것이 아니라 승인 결과가 옮기는 것이라
     * 지목할 사람이 없다(`D7`).
     */
    @Transactional
    public void markPaid(long orderId, String reason) {
        movePayment(orderId, Payment.PAID, Actor.system(reason));
    }

    /**
     * 결제가 거절됐다.
     *
     * <p>그 주문의 셀러 주문이 같이 닫히고 재고가 돌아온다 — {@link #movePayment} 의 곁가지다.
     */
    @Transactional
    public void markPaymentFailed(long orderId, String reason) {
        movePayment(orderId, Payment.PAYMENT_FAILED, Actor.system(reason));
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
        moveShipment(sellerOrderId, to, actor, null);
    }

    /**
     * 반품 사유를 실어 옮긴다.
     *
     * <p><b>{@code RETURN_REQUESTED} 에만 쓴다.</b> 그 밖의 전이는 사유가 없어서
     * {@code null} 이 오고, 오버로드가 그것을 대신 넘긴다.
     *
     * @param returnReason 반품 접수의 사유. 없으면 단순 변심으로 본다(`V29`)
     */
    @Transactional
    public void moveShipment(long sellerOrderId, Shipment to, Actor actor,
            ReturnReason returnReason) {

        Shipment from = Shipment.of(currentShipmentStatus(sellerOrderId));
        require(OrderTransitions.allows(from, to), from.code(), to.code(), actor);

        applyShipment(sellerOrderId, from, to, actor, returnReason);
    }

    private void applyShipment(long sellerOrderId, Shipment from, Shipment to, Actor actor,
            ReturnReason returnReason) {

        if (to == Shipment.RETURN_REQUESTED) {
            // 사유를 안 주면 단순 변심이다. 하자 반품은 요청이 그렇다고 말해야 열린다 —
            // 기본값을 하자로 두면 7일과 제한 검사가 아무에게도 안 걸린다.
            ReturnReason reason = returnReason == null ? ReturnReason.CHANGE_OF_MIND : returnReason;

            requireWithdrawable(sellerOrderId, reason);
            recordReturnReason(sellerOrderId, reason);
        }

        if (to == Shipment.CANCELLED) {
            // 되돌리기 전에 옮긴다. 상태가 먼저 바뀌어야 같은 셀러 주문을 두 번 취소하는 요청이
            // 두 번째에 전이표에 걸린다 — 안 그러면 재고가 두 번 늘어난다.
            updateShipmentStatus(sellerOrderId, to);
            restoreStock(sellerOrderId);
        } else {
            updateShipmentStatus(sellerOrderId, to);
        }

        if (to == Shipment.SHIPPING) {
            markShipped(sellerOrderId);
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
                    Actor.system("결제가 %s 로 끝나 자동 취소".formatted(reason.code())), null);
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
    /**
     * 사유를 묶음에 남긴다.
     *
     * <p>상태만 남기면 <b>어느 조항으로 받은 것인지가 사라진다.</b> 반품 비용 부담이 사유로
     * 갈리고(제18조제9항·제10항), 셀러 평가도 하자율을 봐야 한다.
     *
     * <p>{@code seller_order_return_reason_required_check} 가 같은 것을 한 층 아래에서 막는다.
     */
    private void recordReturnReason(long sellerOrderId, ReturnReason reason) {
        jdbc.sql("""
                        update seller_order set return_reason = :reason
                         where seller_order_id = :sellerOrderId
                        """)
                .param("reason", reason.code())
                .param("sellerOrderId", sellerOrderId)
                .update();
    }

    private void requireWithdrawable(long sellerOrderId, ReturnReason reason) {
        if (reason == ReturnReason.DEFECT) {
            requireWithinDefectPeriod(sellerOrderId);
            return;
        }
        requireWithinWithdrawalPeriod(sellerOrderId);
        requireNoRestrictedItem(sellerOrderId);
    }

    /**
     * 단순 변심의 기한(제17조제1항). 배송완료 때 박제한 값을 읽는다.
     *
     * <p>여기서 다시 계산하면 그 사이 임시공휴일이 추가됐을 때 지나간 주문의 기한까지 흔들린다(`D10`).
     */
    private void requireWithinWithdrawalPeriod(long sellerOrderId) {
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
    }

    /**
     * 하자 반품의 기한(제17조제3항). <b>공급받은 날부터 3개월</b>이다.
     *
     * <p><b>박제를 안 한다</b>(사용자 선택). 기한 셋을 박제한 근거는 영업일 계산이 달력을 타서
     * 지난 기한이 흔들리는 것이었는데(`D10`), 3개월은 역일이라 언제 세도 같은 값이 나온다.
     * 박제할 이유가 없는 값을 박제하면 같은 사실이 두 곳에 생긴다.
     *
     * <p><b>「안 날 또는 알 수 있었던 날부터 30일」은 안 건다</b>(`D2` R3). 그 날은 소비자의
     * 인식이라 우리가 관찰할 수 없고, 제17조제5항이 다툼의 입증책임을 우리에게 지웠다.
     * 짧게 지는 쪽이 안전한 방향이다 — 3개월만 보면 우리가 더 받아 주는 것이고,
     * 30일을 우리가 계산해서 자르면 그것이 틀렸을 때 권리를 뺏은 것이 된다.
     */
    private void requireWithinDefectPeriod(long sellerOrderId) {
        OffsetDateTime deliveredAt = jdbc.sql("""
                        select delivered_at from seller_order
                         where seller_order_id = :sellerOrderId
                        """)
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> rs.getObject("delivered_at", OffsetDateTime.class))
                .single();

        if (deliveredAt == null) {
            return;
        }

        OffsetDateTime expireAt = deliveredAt.plusMonths(DEFECT_WITHDRAWAL_MONTHS);
        if (expireAt.isBefore(OffsetDateTime.now())) {
            throw new ShopException(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED,
                    "하자 반품 기간이 %s 에 끝났다".formatted(expireAt));
        }
    }

    /**
     * 청약철회가 제한된 항목이 들어 있나(제17조제2항, `D2` R4).
     *
     * <p><b>주문 시점에 박제한 값을 읽는다</b>(`Q5`). 상품의 지금 값을 읽으면 셀러가 나중에
     * 제한을 켜서 <b>지나간 주문까지 막을 수 있다</b> — 가격·수수료율·리드타임을 전부
     * 박제해 온 이유와 같다(`D10`).
     *
     * <p>박제할 때 이미 성립 조건을 봤다. 여기 남아 있는 값은 <b>이 거래에서 실제로 성립한 제한</b>이라
     * 사유를 다시 안 가른다.
     *
     * <p><b>하자 반품에는 안 부른다.</b> 제17조제3항이 「제1항 <b>및 제2항</b>에도 불구하고」로
     * 시작해서 이 제한을 통째로 비켜 간다 — 제2항은 <b>멀쩡한 물건을 무르는 것</b>을 막는
     * 규정이라, 물건이 약속과 다른 경우에는 적용될 자리가 아니다.
     *
     * <p>제한 사유 하나라도 걸리면 묶음 전체를 막는다. 취소·반품의 최소 단위가 셀러 묶음이라
     * (`D7`) 항목별로 못 가른다.
     */
    private void requireNoRestrictedItem(long sellerOrderId) {
        String restriction = jdbc.sql("""
                        select oi.withdrawal_restriction_reason
                          from order_item oi
                         where oi.seller_order_id = :sellerOrderId
                           and oi.withdrawal_restriction_reason is not null
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

        Long orderId = jdbc.sql("select order_id from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();

        for (Long[] item : items) {
            // 되돌리는 것도 이동이다(`53`). 이력을 보면 「나갔다가 돌아왔다」가 두 줄로 남는다 —
            // 차감을 지우면 그 사이에 재고가 잡혀 있었다는 사실이 사라진다.
            jdbc.sql("select move_stock(:skuId, :quantity, 'order_cancelled', :orderId)")
                    .param("skuId", item[0])
                    .param("quantity", item[1].intValue())
                    .param("orderId", orderId)
                    .query(Boolean.class)
                    .single();
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
                .param("withdrawal", BusinessCalendar.endOfDay(withdrawalLastDay))
                .param("autoConfirm", BusinessCalendar.endOfDay(autoConfirmLastDay))
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
     * 실제로 보낸 시각을 남긴다(`D2` R21).
     *
     * <p><b>이것이 없으면 지연이 「지금 {@code preparing} 이면서 기한을 넘긴 것」으로만 표현되고,
     * 늦게라도 보내는 순간 흔적이 사라진다.</b> 상습적으로 늦는 셀러와 한 번 늦은 셀러가
     * 데이터에서 같아 보이고, 그러면 제재의 근거가 없다.
     *
     * <p>{@code delivered_at} 이 이미 같은 모양이다 — 배송 소요일 같은 지표도 그 둘의 차로 나온다.
     */
    private void markShipped(long sellerOrderId) {
        jdbc.sql("update seller_order set shipped_at = now() where seller_order_id = :sellerOrderId")
                .param("sellerOrderId", sellerOrderId)
                .update();
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
        long historyId = jdbc.sql("""
                        insert into order_status_history (order_id, seller_order_id, from_status, to_status,
                                                          actor_type, actor_user_id)
                        values (:orderId, :sellerOrderId, :from, :to, :actorType, :actorUserId)
                        returning order_status_history_id
                        """)
                .param("orderId", orderId)
                .param("sellerOrderId", sellerOrderId)
                .param("from", from)
                .param("to", to)
                .param("actorType", actor.type())
                .param("actorUserId", actor.userId())
                .query(Long.class)
                .single();

        writeNote(historyId, actor.reason());
    }

    /**
     * 전이 사유 글을 {@code order_status_history_note} 에 남긴다(`5i-3`).
     *
     * <p><b>5년 표에 안 둔다.</b> 사람이 쓴 글이라 「고객이 010-… 로 연락 와서 취소」 같은 것이
     * 섞여 들어오고, 섞이면 거래기록이 5년을 사는 동안 그 연락처도 같이 산다.
     *
     * <p><b>사유가 없으면 행을 안 만든다.</b> 대부분의 전이는 사유가 없다 —
     * 빈 행을 만들면 파기가 셀 대상만 늘고 「사유가 있었나」가 흐려진다.
     *
     * <p>관리자 전이에 사유가 없으면 <b>커밋 때 지연 제약 트리거가 막는다</b>(`V49`).
     */
    private void writeNote(long historyId, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }

        jdbc.sql("""
                        insert into order_status_history_note (order_status_history_id, reason)
                        values (:id, :reason)
                        """)
                .param("id", historyId)
                .param("reason", reason)
                .update();
    }
}
