package com.projectshop.shop.support;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <p><b>기준일을 스스로 정하지 않는다</b>(`Q287`). 실패한 행이 든 기준일을 그대로 다시 돈다 —
 * 전에는 오늘 기준일만 물어서 기준일이 어제·전달 말일인 배치 셋(일별 매출·정산 마감·접속기록 점검)이
 * 일시적으로 실패해도 재시도가 영영 안 걸렸다.
 *
 * <p>창을 넘기면 사람이 손으로 건다. 자동 알림은 청크 54 라 지금은 `ERROR` 로그가 유일한 신호다.
 */
@Component
public class BatchRetrySweeper {

    /** 한 회차에 이만큼까지 시도한다. 첫 실행을 포함한 수다 — 재시도는 10분 간격 2회(`D19`) */
    static final int MAX_ATTEMPTS = 3;

    /**
     * 마지막 시도가 이 안인 회차만 다시 돈다. 재시도 2회(10분 간격)보다 넉넉하고 기동이 끼어도 남는다.
     * 그 앞의 실패는 다음 회차나 사람 손이 집는다 — 오래된 실패를 다시 돌리면 그새 바뀐 데이터 위에서 돈다
     */
    static final Duration WINDOW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(BatchRetrySweeper.class);

    private final Map<String, RetryableBatch> batches;
    private final BatchRuns runs;

    BatchRetrySweeper(List<RetryableBatch> batches, BatchRuns runs) {
        this.batches = batches.stream()
                .collect(Collectors.toUnmodifiableMap(RetryableBatch::batchName, Function.identity()));
        this.runs = runs;
    }

    /**
     * 10분마다 지난 24시간 안에 실패한 회차를 훑는다.
     *
     * <p>{@code fixedDelay} 다. 앞 회차가 늦어져도 겹치지 않고, 재시도가 재시도를 부르는 자리가 없다.
     *
     * @return 다시 돌린 회차 수
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    public int sweep() {
        int retried = 0;
        OffsetDateTime since = OffsetDateTime.now().minus(WINDOW);
        for (BatchRuns.RetryCandidate candidate : runs.retryCandidates(since, MAX_ATTEMPTS)) {
            RetryableBatch batch = batches.get(candidate.batchName());
            if (batch == null) {
                // 재시도를 안 받는 배치(5분 주기)도 이력은 안 남기지만, 이름이 바뀐 배치의 옛 행은 남을 수 있다
                log.warn("{} 재시도 기준일={} — 재시도를 받는 배치가 아니라 건너뛴다", candidate.batchName(),
                        candidate.baselineDate());
                continue;
            }
            log.warn("{} 재시도 기준일={} — 앞 회차가 일시적으로 실패했다", candidate.batchName(), candidate.baselineDate());
            batch.runFor(candidate.baselineDate());
            retried++;
        }
        return retried;
    }
}
