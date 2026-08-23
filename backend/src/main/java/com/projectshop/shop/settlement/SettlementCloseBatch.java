package com.projectshop.shop.settlement;

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
 * 정산 마감을 매월 1일 05:00 KST 에 돌리고 회차를 {@code batch_run} 에 남긴다(청크 19).
 *
 * <h2>첫 체인이다</h2>
 *
 * <p>`D19` 3층이 코드 없이 규칙만 있던 자리다. <b>자동 구매확정이 그날 성공하지 않았으면
 * 확정 안 된 건이 정산에서 통째로 빠진다</b> — 셀러가 받을 돈을 한 달 늦게 받는데
 * 정산서는 그것대로 합이 맞아서 <b>어디에서도 안 드러난다.</b>
 *
 * <h2>기준일이 실행일이 아니다</h2>
 *
 * <p><b>대상 기간의 마지막 날</b>이다. 체인 판정이 「같은 기준일에 선행이 성공했나」라
 * (`D19`) 실행일로 잡으면 <b>말일의 자동 구매확정이 안 돈 것을 못 본다</b> —
 * 1일 회차만 보고 통과한다. 그 말일이 바로 이번 정산에 들어갈 마지막 확정분이 나온 날이다.
 *
 * <h2>04:00 이 아닌 이유</h2>
 *
 * <p>선행인 자동 구매확정이 04:00 이다(`D10`). 같은 시각에 예약하면 스케줄러가 순서를
 * 보장하지 않아서, 그날 확정분이 이번 마감에 들어갈지가 회차마다 갈린다.
 *
 * <h2>규칙은 여기 없다</h2>
 *
 * <p>무엇을 어떤 줄로 담는지는 {@link SettlementService} 가 정하고 이 클래스는 언제 부르나만
 * 안다(`D23` 「배치는 규칙을 갖지 않는다」). {@code @Scheduled} 를 서비스에 안 붙인 것도
 * 같은 이유고, 붙이면 <b>실패한 회차가 마감과 한 트랜잭션이라 롤백돼서 이력에 안 남는다.</b>
 */
@Component
public class SettlementCloseBatch implements RetryableBatch {

    private static final Logger log = LoggerFactory.getLogger(SettlementCloseBatch.class);

    /** 카탈로그(`D19`)가 부르는 이름. {@code batch_run.batch_name} 에 그대로 들어간다 */
    static final String BATCH_NAME = "settlement_close";

    /** 선행. 이 배치가 같은 기준일에 성공해 있어야 마감이 돈다({@code OrderStatusBatch}) */
    static final String REQUIRED_BATCH = "auto_confirm";

    private final SettlementService settlements;
    private final BatchRuns runs;

    SettlementCloseBatch(SettlementService settlements, BatchRuns runs) {
        this.settlements = settlements;
        this.runs = runs;
    }

    /**
     * 매월 1일 05:00 KST 에 전달을 마감한다.
     *
     * <p>기준일은 <b>어제</b>다 — 1일에 도니까 어제가 곧 전달 말일이다.
     * 그 값을 {@code minusDays(1)} 로 구하는 이유는 달마다 말일이 달라서다.
     */
    @Scheduled(cron = "0 0 5 1 * *", zone = "Asia/Seoul")
    public void close() {
        close(LocalDate.now(BusinessCalendar.ZONE).minusDays(1));
    }

    /**
     * 기준일을 받아 그 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다.
     *
     * @param periodEnd 대상 기간의 마지막 날. 그 달 1일부터가 대상이다
     * @return 정산서를 세운 셀러 수. 스킵했거나 이미 성공했거나 실패했으면 빈 값
     */
    public Optional<BatchRuns.Counts> close(LocalDate periodEnd) {
        return runs.recordAfter(BATCH_NAME, REQUIRED_BATCH, periodEnd, () -> {
            int settled = settlements.close(periodEnd);

            if (settled == 0) {
                log.info("정산 마감 배치 — 대상 셀러 없음 기준일={}", periodEnd);
            } else {
                log.info("정산 마감 배치 끝 기준일={} 정산서={}건", periodEnd, settled);
            }
            return BatchRuns.Counts.of(settled);
        });
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    /**
     * 재시도가 부르는 자리(`36a`).
     *
     * <p><b>스킵된 회차가 여기로 다시 온다</b>(`D19` 3층). 그 사이에 자동 구매확정이
     * 2층 재시도로 성공해 있으면 이어지고, 아니면 스킵을 한 줄 더 남긴다.
     */
    @Override
    public void runFor(LocalDate baselineDate) {
        close(baselineDate);
    }
}
