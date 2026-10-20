package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 접속기록 점검이 열람자별 정보주체 수를 세어 임계 이상을 표시하는지 본다(`Q280b`, 안전성 확보조치 기준 제8조②).
 *
 * <p>행은 SQL 로 바로 넣는다 — 기간 경계를 재려면 {@code created_at} 을 정해야 하는데 기록 입구는 그것을 안 받는다.
 * 정보주체 번호에 외래키가 없어서 계정을 안 만들어도 된다.
 */
class AuditLogReviewServiceTest extends PostgresTestBase {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END_EXCLUSIVE = LocalDate.of(2026, 10, 1);
    private static final OffsetDateTime INSIDE = OffsetDateTime.parse("2026-09-15T10:00:00+09:00");

    private static final long VIEWER = 900_001L;
    private static final long ADMIN = 900_002L;

    @Autowired
    AuditLogReviewService reviews;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("50명 미만이면 표시 0 이고 점검 행은 남는다")
    void belowThresholdLeavesReviewRow() {
        insertViews(VIEWER, null, AuditLogReviewService.REVIEW_SUBJECT_THRESHOLD - 1, INSIDE);

        assertThat(reviews.review(START, END_EXCLUSIVE)).isEmpty();

        Map<String, Object> row = jdbc.sql("""
                select actor_user_id, detail::text as detail from audit_log
                 where event_type = :eventType
                """)
                .param("eventType", AuditLogReviewService.REVIEWED)
                .query()
                .singleRow();
        assertThat(row.get("actor_user_id")).as("시스템이 한 점검이다").isNull();
        assertThat(row.get("detail").toString())
                .contains("\"viewer_count\": 1")
                .contains("\"flagged\": []");
    }

    @Test
    @DisplayName("51명이면 그 열람자가 표시된다")
    void aboveThresholdIsFlagged() {
        insertViews(VIEWER, null, 51, INSIDE);

        assertThat(reviews.review(START, END_EXCLUSIVE))
                .containsExactly(new AuditLogReviewService.ViewerCount(VIEWER, 51, 51));
    }

    @Test
    @DisplayName("대행 열람은 시킨 관리자로 센다")
    void impersonatedViewsCountAsAdmin() {
        insertViews(VIEWER, ADMIN, 51, INSIDE);

        List<AuditLogReviewService.ViewerCount> flagged = reviews.review(START, END_EXCLUSIVE);

        assertThat(flagged)
                .as("요청의 주인은 그 사용자지만 실제로 본 사람은 관리자다")
                .extracting(AuditLogReviewService.ViewerCount::viewerUserId)
                .containsExactly(ADMIN);
    }

    @Test
    @DisplayName("기간 밖 행은 안 센다")
    void rowsOutsidePeriodAreNotCounted() {
        insertViews(VIEWER, null, 51, OffsetDateTime.parse("2026-08-31T23:59:59+09:00"));
        insertViews(VIEWER, null, 51, OffsetDateTime.parse("2026-10-01T00:00:00+09:00"));

        assertThat(reviews.review(START, END_EXCLUSIVE))
                .as("앞뒤 경계 바로 밖이다. 첫날 00:00 KST 이상, 끝 다음 날 00:00 KST 미만만 센다")
                .isEmpty();
    }

    /** 열람자 하나가 서로 다른 정보주체 {@code subjects} 명을 한 건씩 연 기록을 넣는다 */
    private void insertViews(long viewer, Long impersonator, int subjects, OffsetDateTime at) {
        for (int i = 1; i <= subjects; i++) {
            jdbc.sql("""
                    insert into audit_log (event_type, actor_user_id, impersonator_user_id, target_type, target_id, created_at)
                    values (:eventType, :viewer, :impersonator, 'user', :subject, :at)
                    """)
                    .param("eventType", AuditLog.PERSONAL_DATA_VIEWED)
                    .param("viewer", viewer)
                    .param("impersonator", impersonator)
                    .param("subject", 800_000L + i)
                    .param("at", at)
                    .update();
        }
    }
}
