package com.projectshop.shop.support;

import java.time.LocalDate;

/**
 * 회차 재시도를 받는 배치. {@link BatchRetrySweeper} 가 이 목록을 훑는다(`D19` 2층).
 *
 * <p><b>기준일이 있는 배치만 이것을 단다.</b> 5분마다 도는 배치는 다음 회차가 곧 재시도라
 * 층을 얹으면 같은 일을 두 군데서 정하는 것이 된다(`D19`).
 *
 * <p><b>재시도는 같은 기준일로 다시 도는 것</b>이지 새 회차가 아니다. 그래서 시각이 아니라
 * 날짜를 받는다 — 04:00 에 실패하고 04:10 에 다시 돌아도 대상이 같아야 한다.
 */
public interface RetryableBatch {

    /** 카탈로그(`D19`)에 적힌 이름. {@code batch_run.batch_name} 과 같은 값이다 */
    String batchName();

    /** 그 회차를 처음부터 다시 돈다. 두 번 돌아도 결과가 같아야 한다 */
    void runFor(LocalDate baselineDate);
}
