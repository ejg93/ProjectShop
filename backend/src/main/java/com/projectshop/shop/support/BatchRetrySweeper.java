package com.projectshop.shop.support;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일시적으로 실패한 회차를 10분마다 다시 돌린다(`D19` 2층).
 *
 * <p><b>무조건 재시도하지 않는다.</b> 연결이 끊긴 것과 데이터가 틀린 것은 다음에 할 일이 다르다 —
 * 결정적 실패를 다시 돌리면 같은 자리에서 세 번 죽고 로그가 세 배가 된다.
 * 그 판단은 실패한 순간에 {@code BatchRuns} 가 내려서 {@code batch_run.failure_kind} 에 적어 둔다.
 *
 * <p><b>상태를 메모리에 안 둔다.</b> 몇 번 시도했나는 이력의 행 수고, 그래서 기동으로 안 사라진다 —
 * 재시도 횟수를 필드로 들고 있으면 기동한 순간 모든 회차가 처음부터 다시 셋을 쓴다.
 *
 * <p>창을 넘기면 사람이 손으로 건다. 자동 알림은 청크 54 라 지금은 `ERROR` 로그가 유일한 신호다.
 */
@Component
public class BatchRetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(BatchRetrySweeper.class);

    /** 한 회차에 이만큼까지 시도한다. 첫 실행을 포함한 수다(`D19` — 10분 간격 3회) */
    static final int MAX_ATTEMPTS = 3;

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final List<RetryableBatch> batches;
    private final BatchRuns runs;

    BatchRetrySweeper(List<RetryableBatch> batches, BatchRuns runs) {
        this.batches = batches;
        this.runs = runs;
    }

    /**
     * 10분마다 오늘 회차를 훑는다.
     *
     * <p>{@code fixedDelay} 다. 앞 회차가 늦어져도 겹치지 않고, 재시도가 재시도를 부르는 자리가 없다.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    public void sweep() {
        sweep(LocalDate.now(KST));
    }

    /**
     * @param baselineDate 이 회차를 다시 돌린다. 테스트가 날짜를 통제한다
     * @return 다시 돌린 배치 수
     */
    public int sweep(LocalDate baselineDate) {
        int retried = 0;
        for (RetryableBatch batch : batches) {
            if (!runs.shouldRetry(batch.batchName(), baselineDate, MAX_ATTEMPTS)) {
                continue;
            }
            log.warn("{} 재시도 기준일={} — 앞 회차가 일시적으로 실패했다", batch.batchName(), baselineDate);
            batch.runFor(baselineDate);
            retried++;
        }
        return retried;
    }
}
