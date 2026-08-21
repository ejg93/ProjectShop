package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 실패한 회차를 다시 돌리되, 다시 해도 소용없는 것은 안 건드리는가(`36a`, `D19` 2층).
 *
 * <p><b>여기서 지키는 것은 「무조건 재시도하지 않는다」다.</b> 결정적 실패를 다시 돌리면
 * 같은 자리에서 세 번 죽고 로그가 세 배가 된다 — 그때 사람이 보는 것은 진짜 원인이 아니라
 * 같은 줄 셋이다.
 *
 * <p>대상 배치는 개인정보 파기를 쓴다. 실제로 도는 배치라 재시도가 도는지까지 같이 밟힌다 —
 * 데이터가 없어도 성공으로 끝나므로 회차 하나가 남는다.
 */
@DisplayName("배치 회차 재시도")
class BatchRetrySweeperTest extends PostgresTestBase {

    private static final String BATCH = "account_purge";
    private static final LocalDate BASELINE = LocalDate.of(2026, 8, 21);

    @Autowired
    private BatchRetrySweeper sweeper;

    @Autowired
    private JdbcClient jdbc;

    private void insertFailure(String kind) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               status, failure_reason, failure_kind)
                        values (:name, :baselineDate, :now, :now, 'failed', 'QueryTimeoutException', :kind)
                        """)
                .param("name", BATCH)
                .param("baselineDate", BASELINE)
                .param("now", now)
                .param("kind", kind)
                .update();
    }

    private int runCount(String status) {
        return jdbc.sql("""
                        select count(*) from batch_run
                         where batch_name = :name and baseline_date = :baselineDate and status = :status
                        """)
                .param("name", BATCH)
                .param("baselineDate", BASELINE)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    @Nested
    @DisplayName("다시 도는 것")
    class Retries {

        @Test
        @DisplayName("일시적으로 실패한 회차는 다시 돌아서 성공으로 닫힌다")
        void retriesTransientFailure() {
            insertFailure(BatchRuns.TRANSIENT);

            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("연결이 끊긴 것은 10분 뒤에 다시 해 보면 된다(`D19` 2층)")
                    .isEqualTo(1);
            assertThat(runCount("succeeded")).isEqualTo(1);
        }

        @Test
        @DisplayName("두 번째 실패까지는 다시 돈다")
        void retriesUntilAttemptCap() {
            insertFailure(BatchRuns.TRANSIENT);
            insertFailure(BatchRuns.TRANSIENT);

            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("첫 실행을 포함해 셋까지다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("안 도는 것")
    class Skips {

        @Test
        @DisplayName("결정적 실패는 다시 안 돈다")
        void skipsPermanentFailure() {
            insertFailure(BatchRuns.PERMANENT);

            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("데이터가 틀린 것은 다시 해도 같은 자리에서 죽는다")
                    .isZero();
            assertThat(runCount("succeeded")).isZero();
        }

        @Test
        @DisplayName("세 번 시도했으면 포기한다")
        void givesUpAfterThreeAttempts() {
            insertFailure(BatchRuns.TRANSIENT);
            insertFailure(BatchRuns.TRANSIENT);
            insertFailure(BatchRuns.TRANSIENT);

            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("창을 넘기면 사람이 손으로 건다(`D19`)")
                    .isZero();
        }

        @Test
        @DisplayName("이미 성공한 회차는 안 건드린다")
        void skipsSucceededRun() {
            insertFailure(BatchRuns.TRANSIENT);
            sweeper.sweep(BASELINE);

            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("성공 회차가 둘이 될 수 없다는 것을 스키마도 막는다(`36`)")
                    .isZero();
        }

        @Test
        @DisplayName("한 번도 안 돈 회차는 재시도가 아니다")
        void skipsUntriedRun() {
            assertThat(sweeper.sweep(BASELINE))
                    .describedAs("그건 스케줄이 할 일이지 「다시」가 아니다")
                    .isZero();
        }
    }
}
