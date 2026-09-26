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

    /** 주인이 없는 대상. `all` 만 덮는다 */
    private static final Target NO_OWNER = Target.of(-1L, -1L);

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
     * @param baselineDate 돌릴 기준일. 오늘(KST) 앞이어야 하고 없으면 어제
     * @throws ShopException 권한이 없으면 {@code BATCH_FORBIDDEN}, 이름이 목록에 없으면 {@code BATCH_NOT_FOUND},
     *     기준일이 오늘이거나 미래면 {@code VALIDATION_FAILED}
     */
    public BatchRun run(long userId, String batchName, LocalDate baselineDate) {
        // **주인이 없는 동작이다** — 아무에게도 안 속한 대상으로 잰다(`InquiryQuery.findAll` 과 같은 수).
        // 자기 자신을 대상으로 재면 `own` 부여 하나가 이 문을 연다(마무리 55차 독립 리뷰). 이제 `all` 만 덮는다.
        if (!evaluator.decide(userId, "batch", "run", NO_OWNER).allowed()) {
            throw new ShopException(ErrorCode.BATCH_FORBIDDEN);
        }
        RetryableBatch batch = batches.get(batchName);
        if (batch == null) {
            throw new ShopException(ErrorCode.BATCH_NOT_FOUND, "손으로 돌릴 수 있는 배치가 아니다: " + batchName);
        }

        LocalDate today = LocalDate.now(KST);
        LocalDate date = baselineDate == null ? today.minusDays(1) : baselineDate;
        if (!date.isBefore(today)) {
            // **오늘과 미래는 안 받는다**(마무리 55차 독립 리뷰). 미래 기준일은 보존 기한(`D2` R6)의 끝을 앞당겨
            // 아직 지킬 기록을 지우고, 자동 확정·정산을 앞당긴다. 오늘은 `daily_sales` 가 반쪽 집계를 성공으로 남겨
            // 00:15 의 진짜 회차(어제를 돈다)가 건너뛴다. 지난날은 cron 보다 덜 하므로 안전하다.
            throw new ShopException(ErrorCode.VALIDATION_FAILED, "기준일은 오늘(KST) 앞이어야 한다: " + date);
        }
        long before = runs.lastRunId(batchName, date);

        batch.runFor(date);

        return runs.lastRunAfter(batchName, date, before)
                .map(row -> new BatchRun(row.batchName(), row.baselineDate(), row.status(),
                        row.targetCount(), row.processedCount()))
                .orElseGet(() -> new BatchRun(batchName, date, SKIPPED, null, null));
    }
}
