package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.projectshop.shop.support.BatchRuns;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 점검 지표 복원의 두 갈래를 DB 없이 잰다(`Q284`, PR #106 리뷰).
 *
 * <p>{@code AuditLogReviewBatchTest} 는 {@code restoreFlagged} 를 직접 불러서, 회차 뒤에 다시 읽는 줄이나
 * DB 장애를 삼키는 줄을 지워도 초록이었다. 여기는 그 둘을 {@code BatchRuns} 를 갈아 끼워 잰다.
 */
class AuditLogReviewBatchStartupTest {

    private static final String NAME = AuditLogReviewBatch.BATCH_NAME;

    private final BatchRuns runs = mock(BatchRuns.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final AuditLogReviewBatch batch = new AuditLogReviewBatch(mock(AuditLogReviewService.class), runs, meters);

    @Test
    @DisplayName("DB 를 못 읽어도 복원이 기동을 안 막는다")
    void restoreSurvivesDatabaseOutage() {
        willThrow(new CannotGetJdbcConnectionException("시험 — 연결 없음")).given(runs).lastSucceeded(NAME);

        assertThatCode(batch::restoreFlagged)
                .as("지표 하나 때문에 쇼핑몰이 안 뜨면 안 된다 — WARN 만 남긴다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("회차 뒤에도 기준일이 늦은 회차를 다시 읽는다")
    void reviewRereadsLatestRun() {
        given(runs.record(eq(NAME), any(), any())).willReturn(Optional.of(BatchRuns.Counts.of(0)));
        given(runs.lastSucceeded(NAME)).willReturn(Optional.of(
                new BatchRuns.Run(1L, NAME, LocalDate.of(2026, 9, 30), "succeeded", 3, 3)));

        batch.review(LocalDate.of(2026, 8, 31));

        assertThat(meters.get("shop.audit_log.flagged").gauge().value())
                .as("지난 기준일을 다시 돌려도 지표는 기준일이 늦은 회차를 말한다 — 재기동 앞뒤로 같은 뜻이다")
                .isEqualTo(3);
    }
}
