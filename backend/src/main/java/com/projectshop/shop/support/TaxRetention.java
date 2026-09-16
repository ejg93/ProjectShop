package com.projectshop.shop.support;

import java.time.LocalDate;
import java.time.Month;

/**
 * 장부·증빙을 언제까지 들고 있어야 하나(`D2` R41).
 *
 * <p><b>정산과 배상이 세법이 말하는 장부다.</b> 그래서 거래 종료 5년(`R6`)이 지나도 못 지운다 —
 * 개인정보법 제21조제1항 단서가 「다른 법령에 따라 보존하여야 하는 경우」를 파기 의무에서 뺀다.
 *
 * <p>기간은 <b>법정신고기한이 지난 날부터 5년</b>이고(국세기본법 제85조의3제2항,
 * 부가가치세법 제71조제3항), 부가가치세 확정신고기한은 <b>과세기간이 끝난 후 25일</b>이다
 * (부가가치세법 제49조제1항). 그래서 1기(1~6월)는 7월 25일, 2기(7~12월)는 다음 해 1월 25일이다.
 *
 * <p><b>거래일로 잰다. 지급일로 안 잰다.</b> 조문이 「거래사실이 속하는 과세기간」이라고 하고,
 * 정산 지급일은 주기가 끝난 다음 달이다 — 12월 주기를 지급일로 재면 다음 해 1기로 잡혀
 * <b>6개월을 더 들고 있게 된다.</b> 필요 없이 오래 두는 것도 개인정보법 제21조제1항이 막는다.
 *
 * <p><b>파기가 이것을 부른다</b>(`43a-28b`) — {@code TransactionPurgeService} 가 정산·배상의 만료를 이 값으로 잰다.
 *
 * <p><b>역외거래 7년은 안 담는다.</b> 같은 조문의 괄호지만 우리는 국내 거래만 받는다 —
 * 받게 되는 날 이 클래스가 그 갈림을 들고, 그때까지는 없는 분기를 미리 만들지 않는다.
 */
public final class TaxRetention {

    /** 국세기본법 제85조의3제2항. 역외거래는 7년이고 우리는 그 거래를 안 받는다 */
    private static final int RETENTION_YEARS = 5;

    /** 부가가치세법 제49조제1항 — 과세기간이 끝난 후 25일 */
    private static final int FILING_DAY = 25;

    private TaxRetention() {
    }

    /**
     * 이 거래를 언제까지 보존해야 하나.
     *
     * <p>돌려주는 날 <b>당일까지</b>가 보존 기간이다. 그날이 지나야 지운다 —
     * 부르는 쪽이 {@code retainUntil(거래일) < 기준일} 로 고른다.
     *
     * @param transactionDate 거래사실이 일어난 날(KST). 정산이면 주기의 마지막 날이다
     * @return 보존 만료일. 신고기한 다음날에서 5년을 더한 날
     */
    public static LocalDate retainUntil(LocalDate transactionDate) {
        LocalDate filingDeadline = filingDeadlineOf(transactionDate);

        return filingDeadline.plusDays(1).plusYears(RETENTION_YEARS);
    }

    /**
     * 그 거래가 속한 과세기간의 확정신고기한.
     *
     * <p>과세기간이 반년이다 — 1기는 1~6월, 2기는 7~12월(부가가치세법 제5조).
     */
    private static LocalDate filingDeadlineOf(LocalDate transactionDate) {
        boolean firstHalf = transactionDate.getMonthValue() <= Month.JUNE.getValue();

        return firstHalf
                ? LocalDate.of(transactionDate.getYear(), Month.JULY, FILING_DAY)
                : LocalDate.of(transactionDate.getYear() + 1, Month.JANUARY, FILING_DAY);
    }
}
