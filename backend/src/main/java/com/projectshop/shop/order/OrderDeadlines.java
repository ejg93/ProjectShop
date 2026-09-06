package com.projectshop.shop.order;

import com.projectshop.shop.support.BusinessCalendar;

import java.time.LocalDate;
import java.util.Set;

/**
 * 배송완료가 굳히는 기한 둘. <b>DB 를 모르는 순수 계산이다</b>(청크 {@code Q19}).
 *
 * <p>배송완료 시점에 박제한다. 나중에 다시 계산하면 그 사이 {@code holiday} 표가 바뀌었을 때
 * <b>이미 지난 주문의 기한이 움직인다</b> — 법 기한은 그러면 안 된다.
 *
 * <p><b>왜 떼어 놨나</b>({@code D15} 「새 로직을 짤 때」). 이 계산이 서비스 안에 있는 동안은
 * 확인하려면 컨테이너를 띄워야 했다. 휴일 집합을 인자로 받으면 단위 층에서 도니까,
 * 법 기한(`D2` R3)이 연휴·주말·말일 보정에서 어떻게 되는지를 <b>초 단위로</b> 확인한다.
 * 테스트용 사본이 아니라 <b>운영 경로를 나눈 것</b>이다 — 서비스가 이것을 부른다.
 *
 * @param withdrawalLastDay 청약철회 말일. 이날 24시까지 철회할 수 있다
 * @param autoConfirmLastDay 자동 구매확정일. 이날 24시가 지나면 배치가 확정한다
 */
public record OrderDeadlines(LocalDate withdrawalLastDay, LocalDate autoConfirmLastDay) {

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
     * 배송완료일과 그 언저리의 휴일로 기한 둘을 낸다.
     *
     * <p>초일을 안 넣고 말일 24시에 끝난다. 말일이 쉬는 날이면 다음 영업일로 민다 —
     * 기간이 사용자에게 유리한 쪽으로 늘어난다(`D10`).
     */
    public static OrderDeadlines of(LocalDate deliveredOn, Set<LocalDate> holidays) {
        LocalDate withdrawal =
                BusinessCalendar.nextBusinessDay(deliveredOn.plusDays(WITHDRAWAL_DAYS), holidays);
        return new OrderDeadlines(withdrawal, autoConfirm(deliveredOn, withdrawal, holidays));
    }

    /**
     * 자동확정일.
     *
     * <p>기본은 배송완료 다음날부터 8일째다. 다만 <b>말일 보정이 두 기한을 같은 날로 붙일 수 있다</b> —
     * 청약철회 말일이 쉬는 날이라 하루 밀리면 8일째와 겹친다. 그러면 청약철회가 살아 있는 날
     * 자정에 자동확정 배치가 돌아서, 아직 반품할 수 있는 주문이 확정된다.
     *
     * <p>그래서 <b>청약철회 만료 다음날보다 앞설 수 없다</b>. `D10` 이 정한 것은 8일이라는 날수가 아니라
     * 「청약철회 기간보다 짧으면 안 된다」는 제약이고, 겹치는 날은 그 제약이 이긴다.
     */
    private static LocalDate autoConfirm(
            LocalDate deliveredOn, LocalDate withdrawalLastDay, Set<LocalDate> holidays) {
        LocalDate byCount = deliveredOn.plusDays(AUTO_CONFIRM_DAYS);
        LocalDate afterWithdrawal = withdrawalLastDay.plusDays(1);

        return BusinessCalendar.nextBusinessDay(
                byCount.isAfter(afterWithdrawal) ? byCount : afterWithdrawal, holidays);
    }
}
