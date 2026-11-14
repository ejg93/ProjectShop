package com.projectshop.shop.product;

import java.time.LocalDate;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.RetryableBatch;

/**
 * 재개 예정일이 찬 숨긴 사진을 다시 게시한다(`Q465`, 저작권법 제103조③ 「그 예정일에 재개」 `D2` R42).
 *
 * <p>매일 05:10 KST — 05:00 의 숨긴 사진 파기({@link CopyrightHiddenImagePurgeBatch}) 뒤다. 파기는 재개 예정인 것을 안 지운다.
 */
@Component
public class CopyrightReinstatementBatch implements RetryableBatch {

    /** 회차 이름. `batch_run.batch_name` 과 수동 실행 경로가 이 글자로 찾는다 */
    static final String BATCH_NAME = "copyright_reinstatement";

    private static final Logger log = LoggerFactory.getLogger(CopyrightReinstatementBatch.class);

    private final CopyrightReinstatementService reinstatements;
    private final BatchRuns runs;

    CopyrightReinstatementBatch(CopyrightReinstatementService reinstatements, BatchRuns runs) {
        this.reinstatements = reinstatements;
        this.runs = runs;
    }

    /** 기준일은 오늘이다 — 오늘이 예정일인 것까지 재개한다 */
    @Scheduled(cron = "0 10 5 * * *", zone = "Asia/Seoul")
    public void reinstate() {
        reinstate(LocalDate.now(BusinessCalendar.ZONE));
    }

    /** 기준일을 받아 그 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다 */
    public Optional<BatchRuns.Counts> reinstate(LocalDate baselineDate) {
        return runs.record(BATCH_NAME, baselineDate, () -> {
            int reinstated = reinstatements.reinstateDue(baselineDate);
            log.info("저작권 재개 배치 끝 기준일={} 재개={}", baselineDate, reinstated);
            return BatchRuns.Counts.of(reinstated);
        });
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public void runFor(LocalDate baselineDate) {
        reinstate(baselineDate);
    }
}
