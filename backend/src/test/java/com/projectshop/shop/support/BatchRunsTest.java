package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.account.AccountPurgeBatch;
import com.projectshop.shop.order.TransactionPurgeBatch;

/**
 * 배치 회차가 이력에 남고, 같은 회차가 두 번 반영되지 않는가.
 *
 * <p>여기서 보는 것이 둘이다. 하나는 <b>이력이 남는가</b>고, 다른 하나는
 * <b>파기 배치가 스케줄에 실제로 붙어 있는가</b>다. 뒤엣것이 이 청크가 닫은 구멍이라 —
 * 파기 로직은 청크 5i·10a 에 다 서 있었는데 부르는 자리가 테스트뿐이어서
 * 운영에서는 한 번도 안 돌았다.
 */
@DisplayName("배치 회차 이력")
class BatchRunsTest extends PostgresTestBase {

    private static final LocalDate BASELINE = LocalDate.of(2026, 8, 21);

    @Autowired
    private BatchRuns runs;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountPurgeBatch accountPurge;

    @Autowired
    private TransactionPurgeBatch transactionPurge;

    private Map<String, Object> rowOf(String batchName) {
        return jdbc.sql("""
                        select status, target_count, processed_count, failure_reason, failure_kind
                          from batch_run where batch_name = :name
                        """)
                .param("name", batchName)
                .query()
                .singleRow();
    }

    private int rowCount(String batchName) {
        return jdbc.sql("select count(*) from batch_run where batch_name = :name")
                .param("name", batchName)
                .query(Integer.class)
                .single();
    }

    @Nested
    @DisplayName("회차를 남긴다")
    class Recording {

        @Test
        @DisplayName("성공하면 고른 수와 처리한 수가 같이 남는다")
        void recordsSucceededRun() {
            Optional<BatchRuns.Counts> counts =
                    runs.record("test_batch", BASELINE, () -> new BatchRuns.Counts(5, 3));

            assertThat(counts).contains(new BatchRuns.Counts(5, 3));
            assertThat(rowOf("test_batch"))
                    .containsEntry("status", "succeeded")
                    .containsEntry("target_count", 5)
                    .containsEntry("processed_count", 3)
                    .containsEntry("failure_reason", null);
        }

        @Test
        @DisplayName("본체가 던지면 실패 이유가 종류만 남고 예외는 안 올라온다")
        void recordsFailedRun() {
            Optional<BatchRuns.Counts> counts = runs.record("test_batch", BASELINE, () -> {
                throw new IllegalStateException("주소=서울시 어딘가");
            });

            assertThat(counts).isEmpty();
            // 메시지를 넣으면 개인정보가 표에 들어온다(`D16`). 종류만 남기고 자세한 것은 로그에 있다.
            assertThat(rowOf("test_batch"))
                    .containsEntry("status", "failed")
                    .containsEntry("failure_reason", "IllegalStateException")
                    .containsEntry("target_count", null);
        }

        @Test
        @DisplayName("실패 종류를 SQLSTATE 로 가른다")
        void classifiesFailureBySqlState() {
            runs.record("test_batch", BASELINE, () -> {
                throw new org.springframework.dao.ConcurrencyFailureException("충돌",
                        new java.sql.SQLException("serialization failure", "40001"));
            });

            // 예외 타입으로 가르면 드라이버를 올릴 때 조용히 어긋난다(`D11` 이 `Retries` 에서 정한 것).
            assertThat(rowOf("test_batch")).containsEntry("failure_kind", "transient");
        }

        @Test
        @DisplayName("모르는 실패는 결정적으로 본다")
        void treatsUnknownFailureAsPermanent() {
            runs.record("test_batch", BASELINE, () -> {
                throw new IllegalStateException("데이터가 틀렸다");
            });

            // 모르는 것을 재시도로 두면 같은 자리에서 세 번 죽는다(`D19` 2층).
            assertThat(rowOf("test_batch")).containsEntry("failure_kind", "permanent");
        }

        @Test
        @DisplayName("이미 성공한 회차는 본체를 다시 안 부른다")
        void skipsAlreadySucceededRun() {
            AtomicInteger calls = new AtomicInteger();

            runs.record("test_batch", BASELINE, () -> {
                calls.incrementAndGet();
                return BatchRuns.Counts.of(1);
            });
            Optional<BatchRuns.Counts> second = runs.record("test_batch", BASELINE, () -> {
                calls.incrementAndGet();
                return BatchRuns.Counts.of(1);
            });

            assertThat(second).isEmpty();
            assertThat(calls).hasValue(1);
            assertThat(rowCount("test_batch")).isEqualTo(1);
        }

        @Test
        @DisplayName("기준일이 다르면 다시 돈다")
        void runsAgainOnAnotherBaseline() {
            runs.record("test_batch", BASELINE, () -> BatchRuns.Counts.of(1));
            Optional<BatchRuns.Counts> next =
                    runs.record("test_batch", BASELINE.plusDays(1), () -> BatchRuns.Counts.of(2));

            assertThat(next).contains(BatchRuns.Counts.of(2));
            assertThat(rowCount("test_batch")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("스키마가 막는다")
    class Schema {

        /** 앱이 건너뛰기로 막는 것과 별개로, 성공 행이 둘이 되는 것 자체를 스키마가 거부한다 */
        @Test
        @DisplayName("성공 회차는 배치와 기준일당 하나뿐이다")
        void rejectsSecondSucceededRun() {
            insert("succeeded");

            assertThatThrownBy(() -> insert("succeeded"))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("실패 회차는 여러 번 남는다")
        void allowsRepeatedFailures() {
            insert("failed");
            insert("failed");

            assertThat(rowCount("test_batch")).isEqualTo(2);
        }

        private void insert(String status) {
            OffsetDateTime now = OffsetDateTime.now();
            boolean failed = "failed".equals(status);
            jdbc.sql("""
                            insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                                   target_count, processed_count, status,
                                                   failure_reason, failure_kind)
                            values ('test_batch', :baselineDate, :now, :now,
                                    :count, :count, :status, :reason, :kind)
                            """)
                    .param("baselineDate", BASELINE)
                    .param("now", now)
                    .param("count", "succeeded".equals(status) ? 1 : null)
                    .param("status", status)
                    .param("reason", failed ? "IllegalStateException" : null)
                    .param("kind", failed ? BatchRuns.PERMANENT : null)
                    .update();
        }
    }

    @Nested
    @DisplayName("파기 배치가 실제로 돈다")
    class PurgeBatches {

        /**
         * 스케줄을 반사로 본다. <b>이 청크 전에는 파기 로직이 다 서 있는데 부르는 자리가 없었다</b> —
         * 아무 테스트도 안 깨져서 그 상태가 두 청크를 지나갔다. 여기가 그것을 잡는 자리다.
         */
        @Test
        @DisplayName("개인정보 파기는 매일 04:00 KST 에 붙어 있다")
        void accountPurgeIsScheduledDaily() throws NoSuchMethodException {
            Scheduled scheduled = AccountPurgeBatch.class.getMethod("purge").getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("거래기록 파기는 매월 1일 04:00 KST 에 붙어 있다")
        void transactionPurgeIsScheduledMonthly() throws NoSuchMethodException {
            Scheduled scheduled =
                    TransactionPurgeBatch.class.getMethod("purge").getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 4 1 * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("돌면 카탈로그 이름으로 회차가 남는다")
        void leavesRunsUnderCatalogNames() {
            accountPurge.purge(BASELINE);
            transactionPurge.purge(BASELINE);

            assertThat(rowOf("account_purge")).containsEntry("status", "succeeded");
            assertThat(rowOf("transaction_purge")).containsEntry("status", "succeeded");
        }
    }
}
