package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 접속기록 점검 지표가 재기동 뒤에도 마지막 회차 값을 말하는지 본다(`Q284`, `D16`).
 *
 * <p>기동 이벤트에 기대지 않고 {@code restoreFlagged} 를 직접 부른다 — 시험 컨텍스트는 한 번 뜨고 재사용돼서
 * 이벤트가 돈 시점의 {@code batch_run} 을 시험이 고를 수 없다.
 */
class AuditLogReviewBatchTest extends PostgresTestBase {

    @Autowired
    AuditLogReviewBatch batch;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("기동 때 마지막 성공 회차의 표시 수로 채운다")
    void restoresFromLastSucceededRun() {
        clearRuns();
        insertSucceeded(LocalDate.of(2026, 8, 31), 7);
        insertSucceeded(LocalDate.of(2026, 9, 30), 3);

        batch.restoreFlagged();

        assertThat(flagged())
                .as("기준일이 늦은 회차가 마지막이다 — 재기동해도 0 이 아니라 그 회차의 표시 수를 말한다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("성공 회차가 없으면 0 이다")
    void staysZeroWithoutRuns() {
        clearRuns();
        insertSucceeded(LocalDate.of(2026, 9, 30), 3);
        batch.restoreFlagged();
        clearRuns();

        batch.restoreFlagged();

        assertThat(flagged()).as("앞 값을 남기지 않는다 — 회차가 없으면 지어낼 값이 없어 0 이다").isZero();
    }

    /**
     * 복원이 기동 이벤트에 걸려 있는지 본다(마무리 66차 독립 리뷰). 위 둘은 메서드를 직접 불러서
     * {@code @EventListener} 를 지워도 초록이다 — 그러면 재기동 뒤 0 이 그대로 돌아온다.
     */
    @Test
    @DisplayName("복원은 기동 이벤트에 걸려 있다")
    void restoreRunsOnApplicationReady() throws NoSuchMethodException {
        EventListener listener = AuditLogReviewBatch.class.getDeclaredMethod("restoreFlagged")
                .getAnnotation(EventListener.class);

        assertThat(listener).as("@EventListener 가 없으면 기동 때 아무도 안 부른다").isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    private double flagged() {
        return meters.get("shop.audit_log.flagged").gauge().value();
    }

    /** 시험 트랜잭션 안이라 롤백된다 */
    private void clearRuns() {
        jdbc.sql("delete from batch_run where batch_name = :name")
                .param("name", AuditLogReviewBatch.BATCH_NAME)
                .update();
    }

    private void insertSucceeded(LocalDate baselineDate, int flaggedViewers) {
        jdbc.sql("""
                insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                       target_count, processed_count, status)
                values (:name, :baselineDate, now(), now(), :count, :count, 'succeeded')
                """)
                .param("name", AuditLogReviewBatch.BATCH_NAME)
                .param("baselineDate", baselineDate)
                .param("count", flaggedViewers)
                .update();
    }
}
