package com.projectshop.shop.support;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
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

    /** 10분 뒤에 다시 해 볼 실패 */
    static final String TRANSIENT = "transient";
    /** 다시 해도 같은 자리에서 죽는 실패 */
    static final String PERMANENT = "permanent";

    /** 직렬화 충돌과 교착 희생. 연결 끊김(`08*`)은 앞 두 자리로 본다 */
    private static final Set<String> TRANSIENT_STATES = Set.of("40001", "40P01");

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
            // 종류 판단은 지금 한다 — 나중에 이력만 보고 다시 가르면 판단이 두 벌이 된다(`36a`).
            String kind = failureKindOf(e);
            insert(batchName, baselineDate, startedAt, "failed", null,
                    e.getClass().getSimpleName(), kind);
            log.error("{} 실패 기준일={} 종류={}", batchName, baselineDate, kind, e);
            return Optional.empty();
        }

        try {
            insert(batchName, baselineDate, startedAt, "succeeded", counts, null, null);
        } catch (DuplicateKeyException e) {
            // 부분 유니크가 거부한 것이다. 인스턴스가 둘이면 같은 회차가 동시에 뜬다(`D19`).
            // 파기·전이 배치는 두 번 돌아도 결과가 같아서 여기서 끝내도 되지만,
            // 금액을 더하는 배치가 이 줄을 찍기 시작하면 그때는 분산 잠금이 선행이다.
            log.warn("{} 이력 중복 기준일={} — 같은 회차가 동시에 돈 것이다", batchName, baselineDate);
        }
        return Optional.of(counts);
    }

    /**
     * 그 회차를 다시 돌려야 하나(`D19` 2층).
     *
     * <p>셋을 다 만족해야 한다 — <b>아직 성공한 적이 없고</b>, <b>마지막 실패가 일시적</b>이고,
     * <b>시도 수가 상한 밑</b>이다. 한 번도 안 돈 회차는 여기 안 걸린다: 그건 스케줄이 할 일이지
     * 재시도가 아니고, 실패한 적이 없으니 「다시」가 성립하지 않는다.
     *
     * @param maxAttempts 이 수를 채우면 포기한다. 시도마다 한 행이 남아서 그 수가 곧 시도 수다
     */
    public boolean shouldRetry(String batchName, LocalDate baselineDate, int maxAttempts) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select count(*) filter (where status = 'succeeded') = 0
                           and count(*) > 0
                           and count(*) < :maxAttempts
                           and (array_agg(failure_kind order by batch_run_id desc))[1] = 'transient'
                          from batch_run
                         where batch_name = :name and baseline_date = :baselineDate
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .param("maxAttempts", maxAttempts)
                .query(Boolean.class)
                .single());
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
            String status, Counts counts, String failureReason, String failureKind) {
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status,
                                               failure_reason, failure_kind)
                        values (:name, :baselineDate, :startedAt, :finishedAt,
                                :targetCount, :processedCount, :status,
                                :failureReason, :failureKind)
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .param("startedAt", startedAt)
                .param("finishedAt", OffsetDateTime.now())
                .param("targetCount", counts == null ? null : counts.target())
                .param("processedCount", counts == null ? null : counts.processed())
                .param("status", status)
                .param("failureReason", failureReason)
                .param("failureKind", failureKind)
                .update();
    }

    /**
     * 이 실패를 10분 뒤에 다시 해 볼 것인가(`D19` 2층).
     *
     * <p><b>SQLSTATE 로 가른다. 예외 타입으로 안 가른다</b>(`D11` 이 `Retries` 에서 정한 것과 같다) —
     * Spring 이 같은 원인을 판마다 다른 예외로 감싸서, 타입으로 가르면 드라이버를 올릴 때 조용히 어긋난다.
     *
     * <p>일시적인 것은 셋이다. 연결이 끊긴 것(`08*`), 직렬화 충돌(`40001`),
     * 교착으로 희생된 것(`40P01`). <b>나머지는 전부 결정적으로 본다</b> —
     * 모르는 것을 재시도로 두면 같은 자리에서 세 번 죽고 로그가 세 배가 된다.
     */
    static String failureKindOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null
                        && (state.startsWith("08") || TRANSIENT_STATES.contains(state))) {
                    return TRANSIENT;
                }
            }
        }
        return PERMANENT;
    }
}
