package com.projectshop.shop.batch;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.RetryableBatch;

/**
 * 기준일 배치를 손으로 돌린다(`Q241`, `D19`). cron 을 기다리지 않고 측정(`42`)·부하(`70`)·장애 실험(`68`)이
 * 그 회차를 지금 만든다.
 *
 * <p><b>받는 배치는 {@link RetryableBatch} 뿐이다.</b> 기준일이 있어 회차가 서고, 두 번 돌아도 결과가 같다
 * (그 인터페이스의 약속). 5분 주기 스위퍼는 회차가 없고 다음 주기가 곧 재실행이라 안 연다.
 *
 * <p><b>트랜잭션을 안 연다.</b> 배치가 각자 경계와 잠금({@link BatchRuns#record})을 든다 — 여기서 감싸면
 * 잠금이 풀린 뒤에도 본체가 커밋 전이라 다른 인스턴스가 같은 회차를 한 번 더 돈다.
 *
 * <p><b>동기다</b>(2026-09-26 사용자 (가)). 배치가 초 단위라 결과를 그 자리에서 낸다 — 비동기면 입구가 둘이고
 * 부르는 쪽이 폴링한다.
 */
@Service
public class BatchRunService {

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** {@link BatchRuns#record} 가 줄 없이 건너뛴 회차의 상태. {@code batch_run.status} 의 값과 같다 */
    private static final String SKIPPED = "skipped";

    private final Map<String, RetryableBatch> batches;
    private final BatchRuns runs;
    private final PermissionEvaluator evaluator;

    BatchRunService(List<RetryableBatch> batches, BatchRuns runs, PermissionEvaluator evaluator) {
        this.batches = batches.stream()
                .collect(Collectors.toUnmodifiableMap(RetryableBatch::batchName, Function.identity()));
        this.runs = runs;
        this.evaluator = evaluator;
    }

    /**
     * 돌린 결과. {@code batch_run} 의 그 줄이고, 이번 호출이 줄을 안 남겼으면 상태가 {@code skipped} 인 빈 줄이다.
     *
     * @param status {@code succeeded}·{@code failed}·{@code skipped}
     */
    public record BatchRun(String batchName, LocalDate baselineDate, String status,
            Integer targetCount, Integer processedCount) {}

    /**
     * @param baselineDate 돌릴 기준일. 없으면 오늘(KST)
     * @throws ShopException 권한이 없으면 {@code BATCH_FORBIDDEN}, 이름이 목록에 없으면 {@code BATCH_NOT_FOUND}
     */
    public BatchRun run(long userId, String batchName, LocalDate baselineDate) {
        // 대상 자원이 없는 동작이라 자기 자신을 대상으로 잰다 — 관리자는 `all` 이라 통과하고
        // 고객·셀러는 부여가 없고 감사자는 `V75` 트리거가 단 거부에 걸린다(`V119`).
        if (!evaluator.decide(userId, "batch", "run", Target.ownedBy(userId)).allowed()) {
            throw new ShopException(ErrorCode.BATCH_FORBIDDEN);
        }
        RetryableBatch batch = batches.get(batchName);
        if (batch == null) {
            throw new ShopException(ErrorCode.BATCH_NOT_FOUND, "손으로 돌릴 수 있는 배치가 아니다: " + batchName);
        }

        LocalDate date = baselineDate == null ? LocalDate.now(KST) : baselineDate;
        long before = runs.lastRunId(batchName, date);

        batch.runFor(date);

        return runs.lastRunAfter(batchName, date, before)
                .map(row -> new BatchRun(row.batchName(), row.baselineDate(), row.status(),
                        row.targetCount(), row.processedCount()))
                .orElseGet(() -> new BatchRun(batchName, date, SKIPPED, null, null));
    }
}
