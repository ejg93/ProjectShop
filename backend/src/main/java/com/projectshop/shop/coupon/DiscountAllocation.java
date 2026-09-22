package com.projectshop.shop.coupon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 할인 총액을 줄마다 나눈다(`50`).
 *
 * <p><b>서비스 밖에 둔다.</b> 입력만으로 답이 정해지는 계산이라 자기 클래스로 나가야 시험된다
 * (`D15`) — {@code ArchitectureTest} 가 「서비스의 {@code static} 이 바깥을 하나도 안 부르면
 * 그것은 계산이다」로 그것을 막는다.
 *
 * <h2>내림이고 잔차는 마지막이 먹는다</h2>
 *
 * <p>비례 배분은 나누어떨어지지 않는다. 줄마다 내리면 <b>합이 총 할인액보다 작아지고</b>,
 * 그 차이가 결제액 등식을 깨뜨린다({@code assert_order_amounts}).
 *
 * <h2>넘친 것은 앞으로 되돌려 흘린다</h2>
 *
 * <p>마지막 줄이 작으면 잔차가 그 줄의 값보다 커진다 — 실측 예로
 * {@code [25050, 24850, 100]} 에 정액 49,900 이면 마지막이 101 인데 항목값은 100 이라
 * {@code order_item_discount_amount_check} 가 주문을 500 으로 떨어뜨린다(마무리 43차).
 *
 * <p>남은 것을 앞 줄들이 여유만큼 받아 가고, 그래도 남으면 <b>총 할인액을 그만큼 줄인다</b> —
 * 합이 안 맞는 것보다 덜 깎는 것이 낫다. <b>등식이 먼저다.</b>
 */
final class DiscountAllocation {

    private DiscountAllocation() {
    }

    /** 나눈 결과와 실제 합계. 합계가 요청한 할인보다 작을 수 있다 */
    record Result(long total, List<Long> perLine) {}

    /**
     * @param amounts 줄마다의 금액. 대상이 아닌 줄은 배분을 0 으로 받는다
     * @param targets 대상 줄의 자리. 부담 주체가 정한다
     * @param base    대상 줄 금액의 합
     */
    static Result of(List<Long> amounts, List<Integer> targets, long base, long discount) {
        long total = Math.min(discount, base);

        List<Long> perLine = new ArrayList<>(Collections.nCopies(amounts.size(), 0L));
        long assigned = 0;
        for (int index = 0; index < targets.size() - 1; index++) {
            int lineIndex = targets.get(index);
            long share = total * amounts.get(lineIndex) / base;
            perLine.set(lineIndex, share);
            assigned += share;
        }
        perLine.set(targets.get(targets.size() - 1), total - assigned);

        return new Result(spillBack(perLine, targets, amounts), perLine);
    }

    /**
     * 항목값을 넘은 배분을 앞으로 되돌려 흘리고 <b>실제 합계를 돌려준다</b>.
     *
     * <p><b>넘칠 수 있는 것은 잔차를 먹는 마지막 줄뿐</b>이지만, 되돌려 흘린 것이 앞 줄을
     * 다시 넘길 수 있어서 전부 훑는다.
     */
    private static long spillBack(List<Long> perLine, List<Integer> targets, List<Long> amounts) {
        long overflow = 0;
        for (int index = targets.size() - 1; index >= 0; index--) {
            int lineIndex = targets.get(index);
            long room = amounts.get(lineIndex) - perLine.get(lineIndex);

            if (room < 0) {
                overflow += -room;
                perLine.set(lineIndex, amounts.get(lineIndex));
            } else if (overflow > 0) {
                long take = Math.min(room, overflow);
                perLine.set(lineIndex, perLine.get(lineIndex) + take);
                overflow -= take;
            }
        }

        return perLine.stream().mapToLong(Long::longValue).sum();
    }
}
