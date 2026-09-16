package com.projectshop.shop.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 * <p><b>같은 배치는 한 번에 한 곳에서만 돈다</b>(`Q50`). {@code pg_try_advisory_lock} 을
 * 본체 앞에서 잡고 못 잡으면 {@code SKIPPED} 를 남긴다. <b>인스턴스를 늘리기 전에 필요한 것이
 * 아니다</b> — 배포와 재시작 자체가 두 인스턴스가 겹치는 창이고, 04:00 에 배포하면 크론 셋이 겹친다.
 * 아래 부분 유니크는 <b>본체가 다 돈 뒤에야</b> 걸려서 두 번 계산되는 구간을 못 막는다.
 *
 * <p><b>예외를 안 올린다.</b> 회차가 실패하면 `ERROR` 로 남기고 끝낸다 —
 * 올려도 스케줄러가 같은 줄을 한 번 더 찍을 뿐이고, 파기·전이 배치는 다음 회차가 남은 것을 집는다.
 * 일시적 실패를 10분 간격으로 다시 시도하는 2층은 아직 없다(청크 36a).
 */
@Component
public class BatchRuns {

    private static final Logger log = LoggerFactory.getLogger(BatchRuns.class);

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
    private final BatchLockConnections lockConnections;
    private final MeterRegistry meters;

    BatchRuns(JdbcClient jdbc, BatchLockConnections lockConnections, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.lockConnections = lockConnections;
        this.meters = meters;
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

        // **잠금이 본체 앞이다**(`Q50`). 아래 부분 유니크는 본체가 다 돈 뒤에야 걸려서
        // **두 번 계산되는 구간을 못 막는다** — 정산 마감이 그 구간에서 깨진다(`D19`).
        try (Connection lock = lockConnections.open()) {
            if (!tryLock(lock, batchName)) {
                log.warn("{} 건너뜀 기준일={} — 다른 인스턴스가 같은 배치를 돌리는 중이다",
                        batchName, baselineDate);
                insert(batchName, baselineDate, OffsetDateTime.now(),
                        BatchRunStatus.SKIPPED, null, null, null);
                return Optional.empty();
            }
            try {
                return runBody(batchName, baselineDate, body);
            } finally {
                unlock(lock, batchName);
            }
        } catch (SQLException e) {
            // 잠금 연결을 못 얻었다. 회차를 안 돌린 것이라 실패로 남기고, 일시적이면
            // 재시도 창(`D19` 2층)이 집는다. 예외를 안 올리는 것은 이 클래스의 규칙 그대로다.
            FailureKind kind = failureKindOf(e);
            insert(batchName, baselineDate, OffsetDateTime.now(), BatchRunStatus.FAILED, null,
                    e.getClass().getSimpleName(), kind);
            log.error("{} 잠금 실패 기준일={} 종류={}", batchName, baselineDate, kind, e);
            return Optional.empty();
        }
    }

    /**
     * 같은 배치가 두 곳에서 동시에 돌지 않게 막는다(`Q50`).
     *
     * <p><b>세션 잠금이다.</b> 트랜잭션 잠금({@code pg_try_advisory_xact_lock})은 트랜잭션이
     * 끝날 때 저절로 풀리는데, 이 클래스는 <b>일부러 트랜잭션 밖</b>이라 붙들 트랜잭션이 없다.
     * 그래서 연결 하나를 본체가 끝날 때까지 쥐고 있다가 {@link #unlock} 이 <b>같은 연결로</b> 푼다 —
     * 다른 연결에서 풀면 안 풀린다. **웅덩이가 아니라 회차마다 새 연결이다**(`BatchLockConnections`) — 재활용하면 앞 회차의 잠금을 쥔 세션이 돌아와 재진입으로 통과한다.
     *
     * <p><b>열쇠가 배치 이름 하나다. 기준일을 안 넣는다.</b> 넣으면 어제 회차를 다시 돌리는 재시도와
     * 오늘 회차가 같이 돌 수 있는데, 둘이 같은 표의 같은 행을 고른다.
     */
    private boolean tryLock(Connection lock, String batchName) throws SQLException {
        try (var statement = lock.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, batchName);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** 잠금을 푼다. <b>본체가 던져도 푼다</b> — 안 풀면 다음 회차가 통째로 건너뛴다 */
    private void unlock(Connection lock, String batchName) {
        try (var statement = lock.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, batchName);
            statement.execute();
        } catch (SQLException e) {
            // 연결을 닫으면 세션 잠금은 어차피 풀린다. 그래도 남기는 것은
            // **풀린 경로가 둘로 갈리는 것**이 이상 신호라서다(`D16`).
            log.warn("{} 잠금 해제 실패 — 연결을 닫아 푼다", batchName, e);
        }
    }

    private Optional<Counts> runBody(String batchName, LocalDate baselineDate,
            Supplier<Counts> body) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        Counts counts;
        try {
            counts = body.get();
        } catch (RuntimeException e) {
            // 예외 종류만 남긴다. 메시지에는 값이 실려 오고 그 값이 개인정보일 수 있다(`D16`).
            // 종류 판단은 지금 한다 — 나중에 이력만 보고 다시 가르면 판단이 두 벌이 된다(`36a`).
            FailureKind kind = failureKindOf(e);
            insert(batchName, baselineDate, startedAt, BatchRunStatus.FAILED, null,
                    e.getClass().getSimpleName(), kind);
            log.error("{} 실패 기준일={} 종류={}", batchName, baselineDate, kind, e);
            return Optional.empty();
        }

        try {
            insert(batchName, baselineDate, startedAt, BatchRunStatus.SUCCEEDED, counts, null, null);
        } catch (DuplicateKeyException e) {
            // 부분 유니크가 거부한 것이다. **잠금이 선 뒤로 이 줄은 안 찍혀야 한다**(`Q50`) —
            // 같은 배치의 동시 실행을 본체 앞에서 막으므로 여기까지 둘이 올 수가 없다.
            // 그래도 남겨 둔다: 찍히면 잠금이 안 걸린 것이라 **그 자체가 신호**다(`D16`).
            log.warn("{} 이력 중복 기준일={} — 같은 회차가 동시에 돈 것이다. "
                    + "잠금이 선 뒤로는 안 나와야 하는 줄이다", batchName, baselineDate);
        }
        return Optional.of(counts);
    }

    /**
     * 선행 배치가 그날 성공했을 때만 회차를 돌린다(`D19` 3층 — 체인).
     *
     * <p><b>안 돌면 스킵을 남긴다.</b> 아무것도 안 하고 끝내면 「선행이 막아서 안 돈 것」과
     * 「스케줄이 안 걸린 것」이 이력에서 안 갈린다 — 다음 회차가 다시 시도할지도 그 값으로 정한다.
     *
     * <p>판정은 <b>같은 기준일</b>로 한다. 그래서 후행의 기준일은 <b>선행이 그 데이터를 만든 날</b>
     * 이어야 한다 — 실행일로 잡으면 지나간 날의 선행 실패를 못 본다.
     *
     * @param requiredBatch 이 배치가 같은 기준일에 성공해 있어야 한다
     * @return 실제로 돌았으면 그 수. 스킵했거나 이미 성공했거나 실패했으면 빈 값
     */
    public Optional<Counts> recordAfter(String batchName, String requiredBatch,
            LocalDate baselineDate, Supplier<Counts> body) {
        if (!succeeded(requiredBatch, baselineDate)) {
            // 사람이 볼 것이라 `WARN` 이다(`D16`). 재시도 창(10분 3회) 안에서 선행이 성공하면 이어진다.
            log.warn("{} 건너뜀 기준일={} — 선행 {} 이 그날 성공하지 않았다",
                    batchName, baselineDate, requiredBatch);
            insert(batchName, baselineDate, OffsetDateTime.now(), BatchRunStatus.SKIPPED, null, null, null);
            return Optional.empty();
        }
        return record(batchName, baselineDate, body);
    }

    /** 그 배치가 그 기준일에 성공한 회차를 남겼나 */
    public boolean succeeded(String batchName, LocalDate baselineDate) {
        return alreadySucceeded(batchName, baselineDate);
    }

    /**
     * 그 회차를 다시 돌려야 하나(`D19` 2층).
     *
     * <p>셋을 다 만족해야 한다 — <b>아직 성공한 적이 없고</b>, <b>마지막 회차가 다시 해 볼 것</b>이고,
     * <b>시도 수가 상한 밑</b>이다. 한 번도 안 돈 회차는 여기 안 걸린다: 그건 스케줄이 할 일이지
     * 재시도가 아니고, 실패한 적이 없으니 「다시」가 성립하지 않는다.
     *
     * <p><b>다시 해 볼 것이 둘이다.</b> 일시적 실패(2층)와 <b>선행이 막아서 스킵된 것</b>(3층)이다.
     * 스킵을 빼면 체인이 성립을 안 한다 — `D19` 가 「스킵된 후행은 재시도 창 안에서 다시 시도한다」고
     * 정한 자리가 여기고, 그 사이에 선행이 재시도로 성공하면 이어진다.
     *
     * @param maxAttempts 이 수를 채우면 포기한다. 시도마다 한 행이 남아서 그 수가 곧 시도 수다
     */
    public boolean shouldRetry(String batchName, LocalDate baselineDate, int maxAttempts) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select count(*) filter (where status = :succeeded) = 0
                           and count(*) > 0
                           and count(*) < :maxAttempts
                           and ((array_agg(failure_kind order by batch_run_id desc))[1] = :transient
                                or (array_agg(status order by batch_run_id desc))[1] = :skipped)
                          from batch_run
                         where batch_name = :name and baseline_date = :baselineDate
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .param("maxAttempts", maxAttempts)
                .param("succeeded", BatchRunStatus.SUCCEEDED.code())
                .param("transient", FailureKind.TRANSIENT.code())
                .param("skipped", BatchRunStatus.SKIPPED.code())
                .query(Boolean.class)
                .single());
    }

    private boolean alreadySucceeded(String batchName, LocalDate baselineDate) {
        return jdbc.sql("""
                        select exists (
                            select 1 from batch_run
                             where batch_name = :name and baseline_date = :baselineDate
                               and status = :succeeded)
                        """)
                .param("name", batchName)
                .param("baselineDate", baselineDate)
                .param("succeeded", BatchRunStatus.SUCCEEDED.code())
                .query(Boolean.class)
                .single();
    }

    private void insert(String batchName, LocalDate baselineDate, OffsetDateTime startedAt,
            BatchRunStatus status, Counts counts, String failureReason, FailureKind failureKind) {
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
                .param("status", status.code())
                .param("failureReason", failureReason)
                .param("failureKind", failureKind == null ? null : failureKind.code())
                .update();

        // 이력 행이 남은 뒤에만 센다(`Q53`). 앞에서 세면 부분 유니크가 거부한 회차까지 세어져서
        // 지표와 `batch_run` 이 갈린다. 태그 둘 다 닫힌 목록이다 — 배치 이름은 카탈로그(`D19`),
        // 상태는 열거형이라 카디널리티가 안 터진다(`D16`).
        Counter.builder("shop.batch.run")
                .tag("name", batchName)
                .tag("status", status.code())
                .description("배치 회차 수. 상태별로 갈라 센다")
                .register(meters)
                .increment();
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
    static FailureKind failureKindOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            // **노출 번호 충돌은 일시적이다**(`Q49`·마무리 17차). 다시 뽑으면 다른 번호가 나온다.
            // SQLSTATE 로는 안 갈린다 — `23505` 는 「다시 해도 같은 유일 위반」과 구분이 안 된다.
            //
            // **이 줄이 없으면 `Q49` 의 결정이 코드에 없다.** 그 청크는 「정산은 배치 안이라
            // 재시도 스위퍼가 받으니 `Retries.onConflict` 로 안 감싼다」고 정했는데,
            // 아래 분류가 `23505` 를 PERMANENT 로 보내서 **스위퍼가 그 회차를 안 집는다.**
            if (cause instanceof ExposedNumber.Conflict) {
                return FailureKind.TRANSIENT;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null
                        && (state.startsWith("08") || TRANSIENT_STATES.contains(state))) {
                    return FailureKind.TRANSIENT;
                }
            }
        }
        return FailureKind.PERMANENT;
    }
}
