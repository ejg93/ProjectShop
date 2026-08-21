package com.projectshop.shop.support;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 배치 회차를 {@code batch_run} 에 남기고, 이미 성공한 회차를 다시 안 돌린다.
 * 배치가 본체를 이 안에 넣어서 부른다.
 *
 * <p><b>트랜잭션에 안 들어간다.</b> 부르는 쪽이 {@code @Transactional} 이면 실패 행이
 * 본체와 같이 롤백돼서, 실패한 회차가 이력에 안 남는다. 그래서 이 클래스를 부르는 자리는
 * 배치 클래스이고 도메인 서비스가 아니다.
 *
 * <p><b>예외를 안 올린다.</b> 회차가 실패하면 `ERROR` 로 남기고 끝낸다 —
 * 올려도 스케줄러가 같은 줄을 한 번 더 찍을 뿐이고, 파기·전이 배치는 다음 회차가 남은 것을 집는다.
 * 일시적 실패를 10분 간격으로 다시 시도하는 2층은 아직 없다(청크 36a).
 */
@Component
public class BatchRuns {

    private static final Logger log = LoggerFactory.getLogger(BatchRuns.class);

    /**
     * 한 회차가 고른 수와 처리한 수.
     *
     * @param target    고른 수
     * @param processed 실제로 처리한 수
     */
    public record Counts(int target, int processed) {

        /** 고른 것이 곧 처리한 것인 배치가 쓴다 — 집합 {@code delete} 는 지운 수가 곧 대상 수다. */
        public static Counts of(int processed) {
            return new Counts(processed, processed);
        }
    }

    private final JdbcClient jdbc;

    BatchRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 회차를 돌리고 이력을 남긴다. 같은 기준일에 이미 성공한 회차가 있으면 본체를 안 부른다.
     *
     * @param batchName    카탈로그(`D19`)에 적힌 배치 이름
     * @param baselineDate 판단 기준일(KST). 이 값이 같으면 같은 회차다
     * @param body         회차 본체. 고른 수와 처리한 수를 돌려준다
     * @return 실제로 돌았으면 그 수, 건너뛰거나 실패했으면 빈 값
     */
    public Optional<Counts> record(String batchName, LocalDate baselineDate, Supplier<Counts> body) {
        if (alreadySucceeded(batchName, baselineDate)) {
            log.info("{} 건너뜀 기준일={} — 이미 성공한 회차다", batchName, baselineDate);
            return Optional.empty();
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        Counts counts;
        try {
            counts = body.get();
        } catch (RuntimeException e) {
            // 예외 종류만 남긴다. 메시지에는 값이 실려 오고 그 값이 개인정보일 수 있다(`D16`).
            insert(batchName, baselineDate, startedAt, "failed", null, e.getClass().getSimpleName());
            log.error("{} 실패 기준일={}", batchName, baselineDate, e);
            return Optional.empty();
        }

        try {
            insert(batchName, baselineDate, startedAt, "succeeded", counts, null);
        } catch (DuplicateKeyException e) {
            // 부분 유니크가 거부한 것이다. 인스턴스가 둘이면 같은 회차가 동시에 뜬다(`D19`).
            // 파기·전이 배치는 두 번 돌아도 결과가 같아서 여기서 끝내도 되지만,
            // 금액을 더하는 배치가 이 줄을 찍기 시작하면 그때는 분산 잠금이 선행이다.
            log.warn("{} 이력 중복 기준일={} — 같은 회차가 동시에 돈 것이다", batchName, baselineDate);
        }
        return Optional.of(counts);
    }

    private boolean alreadySucceeded(String batchName, LocalDate baselineDate) {
        return jdbc.sql("""
                        select exists (
                            select 1 from batch_run
                             where batch_name = :name and baseline_date = :baselineDate
                               and status = 'succeeded')
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .query(Boolean.class)
                .single();
    }

    private void insert(String batchName, LocalDate baselineDate, OffsetDateTime startedAt,
            String status, Counts counts, String failureReason) {
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status, failure_reason)
                        values (:name, :baselineDate, :startedAt, :finishedAt,
                                :targetCount, :processedCount, :status, :failureReason)
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .param("startedAt", startedAt)
                .param("finishedAt", OffsetDateTime.now())
                .param("targetCount", counts == null ? null : counts.target())
                .param("processedCount", counts == null ? null : counts.processed())
                .param("status", status)
                .param("failureReason", failureReason)
                .update();
    }
}
