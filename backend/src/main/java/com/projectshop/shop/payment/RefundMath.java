package com.projectshop.shop.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 환불 금액을 정하는 계산. <b>DB 도 시계도 안 본다</b>(`Q54`).
 *
 * <p><b>가른 이유가 테스트 레인이다</b>(`D15`). 이 계산들은 {@link RefundService} 안에 있었고,
 * 그래서 「수수료 절사 잔액이 마지막 수량에 몰리나」를 확인하려면 <b>컨테이너를 띄워야</b> 했다.
 * 여기로 나오면 같은 확인이 빠른 레인에서 끝난다 — 자주 못 돌리는 테스트는 있으나 마나다.
 *
 * <p><b>배송비는 여기 없다.</b> {@code shippingFeeRefund} 는 이미 나간 배송비 환급을 조회해야 해서
 * 순수 함수가 아니다 — 서비스에 남는다.
 *
 * <p>값을 받고 값을 돌려주는 것만 한다. 트랜잭션도 판정도 이 바깥이다.
 */
final class RefundMath {

    /** 지연배상금 이율. 연 100분의 15(전자상거래법 시행령 제21조의3, `D2` R5) */
    private static final BigDecimal DELAY_RATE = new BigDecimal("0.15");

    /** 이율이 연 단위라 일수로 쪼갤 때 나누는 수. 윤년을 안 가른다 */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal(365);

    private RefundMath() {
    }

    /** 주문 항목 하나와 그 항목에서 이미 나간 누계 */
    /**
     * 주문 항목 하나와 그 항목에서 이미 나간 누계.
     *
     * @param discountAmount 그 항목에 배분된 할인(`50`). <b>돌려줄 것은 실제로 받은 값</b>이라
     *        이만큼 덜 준다 — 안 빼면 소비자가 낸 것보다 많이 돌려주고, 전액 환불은
     *        결제액 상한에 걸려 <b>아예 막힌다</b>(`Q164`)
     * @param refundedAmount 이미 돌려준 대금 누계. 마지막 수량에서 잔액을 맞추는 데 쓴다
     */
    record Item(long orderItemId, int quantity, long unitPriceInclVat,
            long commissionAmount, long discountAmount, int refundedQuantity,
            long refundedCommission, long refundedAmount) {

        int remaining() {
            return quantity - refundedQuantity;
        }

        /** 이 항목에서 돌려줄 수 있는 전부. 소비자가 그 항목에 실제로 낸 값이다 */
        long refundable() {
            return unitPriceInclVat * quantity - discountAmount;
        }
    }

    /** 이번에 돌려줄 한 항목 */
    record Portion(long orderItemId, int quantity, long amount, long commissionRefund) {}

    /**
     * 무엇을 몇 개 돌려줄지 정하고 금액을 계산한다.
     *
     * <p>요청이 항목을 안 주면 <b>남은 것 전부</b>다. 전액 환불이 흔한 경로라
     * 화면이 항목을 세어 보내게 하면 그 계산이 두 곳에 생긴다.
     */
    static List<Portion> resolvePortions(List<Item> items, List<RefundService.Line> lines) {
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item item : items) {
            byId.put(item.orderItemId(), item);
        }

        List<RefundService.Line> wanted = lines == null || lines.isEmpty()
                ? items.stream().filter(item -> item.remaining() > 0)
                        .map(item -> new RefundService.Line(item.orderItemId(), item.remaining())).toList()
                : lines;

        if (wanted.isEmpty()) {
            throw new ShopException(ErrorCode.REFUND_EXCEEDS_LIMIT, "이미 전부 환불된 묶음이다");
        }

        List<Portion> portions = new ArrayList<>(wanted.size());
        for (RefundService.Line line : wanted) {
            Item item = byId.get(line.orderItemId());

            // 남의 묶음 항목을 끼워 넣는 요청이다. 없는 항목과 같은 답을 준다 —
            // 가르면 항목 번호를 두드려서 남의 주문 구성을 셀 수 있다(`D5`).
            if (item == null) {
                throw new ShopException(ErrorCode.ORDER_NOT_FOUND,
                        "이 묶음의 항목이 아니다: " + line.orderItemId());
            }
            if (line.quantity() <= 0 || line.quantity() > item.remaining()) {
                throw new ShopException(ErrorCode.REFUND_EXCEEDS_LIMIT,
                        "환불할 수 있는 수량은 %d 다: order_item_id=%d"
                                .formatted(item.remaining(), item.orderItemId()));
            }
            portions.add(new Portion(item.orderItemId(), line.quantity(),
                    amountRefund(item, line.quantity()), commissionRefund(item, line.quantity())));
        }
        return portions;
    }

    /**
     * 이 항목에서 포기할 수수료.
     *
     * <p><b>절사 잔액을 마지막 수량에 몰아 준다</b>(사용자 선택). {@code commission_amount} 가
     * 항목 단위로 이미 잘린 값이라(`D8`) 수량으로 또 나누면 1원씩 남는데, 그것을 그대로 두면
     * 통째로 환불했는데 수수료가 덜 돌아가서 <b>정산에 우리 몫이 남는다.</b>
     *
     * <p>마지막 수량인지는 <b>누계로 판단한다</b> — 3개를 1개씩 세 번 돌려주는 것과
     * 한 번에 세 개 돌려주는 것이 같은 값이어야 하고, 그 등식을 테스트가 지킨다
     * (`money-invariants` 「통째로 환불하면 {@code commission_refund = commission_amount}」).
     */
    /**
     * 이 항목에서 돌려줄 대금. <b>배분된 할인을 뺀다</b>(`Q164`).
     *
     * <p><b>잔액을 마지막 수량에 몰아 준다</b> — {@link #commissionRefund} 와 같은 모양이고
     * 같은 이유다. 할인이 항목 단위로 이미 잘린 값이라(`50`) 수량으로 또 나누면 1원씩 남고,
     * 그것을 그대로 두면 <b>통째로 환불했는데 낸 것보다 덜 돌아간다.</b>
     */
    static long amountRefund(Item item, int quantity) {
        boolean last = item.refundedQuantity() + quantity == item.quantity();

        return last
                ? item.refundable() - item.refundedAmount()
                : item.refundable() * quantity / item.quantity();
    }

    static long commissionRefund(Item item, int quantity) {
        boolean last = item.refundedQuantity() + quantity == item.quantity();

        return last
                ? item.commissionAmount() - item.refundedCommission()
                : item.commissionAmount() * quantity / item.quantity();
    }

    /**
     * 기한을 넘긴 만큼 붙는 지연배상금.
     *
     * <p><b>계산이 여기 한 곳이다.</b> 화면이 다시 계산하면 청구액과 표시액이 갈리고,
     * 갈리는 쪽이 법정 금액이라 어느 쪽이 맞는지를 우리가 못 정한다. 결과를 {@code refund} 에
     * 박제해서 나중에 이율이 바뀌어도 지나간 건의 금액이 안 움직이게 한다.
     *
     * <p><b>일 단위로 세고 하루가 안 찼어도 1일로 본다</b>(사용자 선택). 법이 「기간」이라고만 해서
     * 실무 관례를 따랐다 — 한 시간 늦은 것에 0원을 물리면 「늦었는데 배상금이 0」이 된다.
     *
     * <p><b>원 미만은 올린다</b>(사용자 선택). `D8` 은 버림이지만 그것은 <b>우리가 받는 돈</b>의 규칙이고,
     * 이것은 우리가 늦어서 <b>물어 주는 돈</b>이라 방향이 반대다. 버리면 법이 정한 금액보다 적게 준다.
     *
     * @param amount    돌려줄 대금. 이자는 여기에 안 들어 있다
     * @param dueAt     환급 기한. `12a-3` 이 사유별 기산점으로 박아 둔 값이다
     * @param decidedAt 실제로 조치한 시각
     * @return 붙는 이자. 기한 안에 처리했으면 0
     */
    static long delayInterest(long amount, OffsetDateTime dueAt, OffsetDateTime decidedAt) {
        if (!decidedAt.isAfter(dueAt)) {
            return 0;
        }

        long days = ChronoUnit.DAYS.between(dueAt, decidedAt);
        if (Duration.between(dueAt, decidedAt).minusDays(days).isPositive()) {
            days++;
        }

        return BigDecimal.valueOf(amount)
                .multiply(DELAY_RATE)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_IN_YEAR, 0, RoundingMode.CEILING)
                .longValueExact();
    }
}
